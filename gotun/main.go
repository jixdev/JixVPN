package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"time"
)

type connKey struct {
	srcIP   uint32
	dstIP   uint32
	srcPort uint16
	dstPort uint16
}

type tunConn struct {
	socks    net.Conn
	key      connKey
	clientISN uint32
	serverISN uint32
	lastAck  uint32
	closed   bool
	mu       sync.Mutex
}

var (
	conns   = make(map[connKey]*tunConn)
	connMu  sync.Mutex
	tunW    *os.File
)

func main() {
	fdStr := os.Getenv("TUN_FD")
	if fdStr == "" {
		fmt.Fprintln(os.Stderr, "TUN_FD not set")
		os.Exit(1)
	}
	fd, err := strconv.Atoi(fdStr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "invalid TUN_FD: %s\n", fdStr)
		os.Exit(1)
	}
	proxyAddr := os.Getenv("SOCKS5_ADDR")
	if proxyAddr == "" {
		proxyAddr = "127.0.0.1:7890"
	}

	tunR := os.NewFile(uintptr(fd), "tun")
	if tunR == nil {
		fmt.Fprintln(os.Stderr, "failed to open TUN fd for read")
		os.Exit(1)
	}
	tunW = os.NewFile(uintptr(fd), "tun-w")
	if tunW == nil {
		fmt.Fprintln(os.Stderr, "failed to open TUN fd for write")
		os.Exit(1)
	}

	fmt.Printf("gotun: TUN_FD=%d SOCKS5=%s\n", fd, proxyAddr)

	buf := make([]byte, 65535)
	for {
		n, err := tunR.Read(buf)
		if err != nil {
			if err != io.EOF {
				fmt.Fprintf(os.Stderr, "tun read: %v\n", err)
			}
			break
		}
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		go handlePacket(pkt, proxyAddr)
	}
}

func ipToUint32(ip []byte) uint32 {
	if len(ip) < 4 {
		return 0
	}
	return binary.BigEndian.Uint32(ip)
}

func uint32ToIP(v uint32) net.IP {
	ip := make(net.IP, 4)
	binary.BigEndian.PutUint32(ip, v)
	return ip
}

func handlePacket(pkt []byte, proxyAddr string) {
	if len(pkt) < 20 {
		return
	}
	ver := (pkt[0] >> 4) & 0x0F
	if ver != 4 {
		return
	}
	ihl := int(pkt[0] & 0x0F)
	if ihl < 5 {
		return
	}
	ipHdrLen := ihl * 4
	if ipHdrLen+20 > len(pkt) {
		return
	}
	protocol := pkt[9]
	if protocol != 6 {
		return
	}

	srcIP := ipToUint32(pkt[12:16])
	dstIP := ipToUint32(pkt[16:20])

	srcPort := binary.BigEndian.Uint16(pkt[ipHdrLen : ipHdrLen+2])
	dstPort := binary.BigEndian.Uint16(pkt[ipHdrLen+2 : ipHdrLen+4])
	tcpHdrLen := int((pkt[ipHdrLen+12] >> 4) & 0x0F)
	if tcpHdrLen < 5 {
		return
	}
	tcpHdrLenBytes := tcpHdrLen * 4
	seqNum := binary.BigEndian.Uint32(pkt[ipHdrLen+4 : ipHdrLen+8])
	ackNum := binary.BigEndian.Uint32(pkt[ipHdrLen+8 : ipHdrLen+12])
	flags := pkt[ipHdrLen+13]
	dataEnd := ipHdrLen + tcpHdrLenBytes
	var data []byte
	if dataEnd < len(pkt) {
		data = pkt[dataEnd:]
	}

	key := connKey{srcIP: srcIP, dstIP: dstIP, srcPort: srcPort, dstPort: dstPort}

	if flags&0x02 != 0 && len(data) == 0 {
		connMu.Lock()
		if _, exists := conns[key]; exists {
			connMu.Unlock()
			return
		}
		connMu.Unlock()

		go handleNewConn(key, dstIP, dstPort, seqNum, proxyAddr, pkt)
		return
	}

	connMu.Lock()
	tc, exists := conns[key]
	connMu.Unlock()
	if !exists {
		return
	}

	tc.mu.Lock()
	if tc.closed {
		tc.mu.Unlock()
		return
	}
	tc.lastAck = seqNum + uint32(len(data))

	if len(data) > 0 {
		_, err := tc.socks.Write(data)
		if err != nil {
			tc.closed = true
			tc.socks.Close()
		}
	}

	if flags&0x01 != 0 || flags&0x04 != 0 {
		tc.closed = true
		go func() {
			time.Sleep(100 * time.Millisecond)
			tc.socks.Close()
		}()
	}
	tc.mu.Unlock()
}

func handleNewConn(key connKey, dstIP uint32, dstPort uint16, clientISN uint32, proxyAddr string, synPkt []byte) {
	socks, err := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
	if err != nil {
		return
	}

	_, err = socks.Write([]byte{5, 1, 0})
	if err != nil {
		socks.Close()
		return
	}
	resp := make([]byte, 2)
	_, err = io.ReadFull(socks, resp)
	if err != nil || resp[0] != 5 || resp[1] != 0 {
		socks.Close()
		return
	}

	dst := make([]byte, 4)
	binary.BigEndian.PutUint32(dst, dstIP)
	req := buildSocks5Connect(dst, dstPort)
	_, err = socks.Write(req)
	if err != nil {
		socks.Close()
		return
	}
	_, err = io.ReadFull(socks, resp[:2])
	if err != nil || resp[1] != 0 {
		socks.Close()
		return
	}
	_, err = io.ReadFull(socks, make([]byte, 4))
	if err != nil {
		socks.Close()
		return
	}

	tc := &tunConn{
		socks:     socks,
		key:       key,
		clientISN: clientISN,
		serverISN: 1000000,
	}

	connMu.Lock()
	conns[key] = tc
	connMu.Unlock()

	go readFromSocks(tc, synPkt)
}

func buildSocks5Connect(dstIP []byte, dstPort uint16) []byte {
	b := []byte{5, 1, 0, 1}
	b = append(b, dstIP...)
	b = append(b, byte(dstPort>>8), byte(dstPort))
	return b
}

func readFromSocks(tc *tunConn, synPkt []byte) {
	defer func() {
		tc.mu.Lock()
		tc.closed = true
		tc.socks.Close()
		tc.mu.Unlock()
		connMu.Lock()
		delete(conns, tc.key)
		connMu.Unlock()
	}()

	srcIP := uint32ToIP(tc.key.dstIP)
	dstIP := uint32ToIP(tc.key.srcIP)
	srcPort := tc.key.dstPort
	dstPort := tc.key.srcPort

	ipHdrLen := int((synPkt[0]&0x0F) * 4)
	tcpHdrLen := int((synPkt[ipHdrLen+12]>>4)&0x0F) * 4

	tcpTemplate := make([]byte, tcpHdrLen)
	copy(tcpTemplate, synPkt[ipHdrLen:ipHdrLen+tcpHdrLen])

	tc.mu.Lock()
	tc.lastAck = tc.clientISN + 1
	tc.mu.Unlock()

	buf := make([]byte, 65535)
	serverSeq := tc.serverISN

	for {
		n, err := tc.socks.Read(buf)
		if err != nil {
			break
		}
		data := make([]byte, n)
		copy(data, buf[:n])

		tc.mu.Lock()
		ack := tc.lastAck
		tc.mu.Unlock()

		pkt := buildIPPacket(srcIP, dstIP, srcPort, dstPort, data, serverSeq, ack, tcpTemplate)
		serverSeq += uint32(n)

		if tunW != nil {
			tunW.Write(pkt)
		}
	}
}

func buildIPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, data []byte, seq, ack uint32, tcpTemplate []byte) []byte {
	totalLen := 20 + 20 + len(data)
	pkt := make([]byte, totalLen)

	pkt[0] = 0x45
	pkt[1] = 0
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	pkt[4] = 0
	pkt[5] = 0
	binary.BigEndian.PutUint16(pkt[6:8], 0)
	pkt[8] = 64
	pkt[9] = 6
	copy(pkt[12:16], srcIP)
	copy(pkt[16:20], dstIP)

	ipChecksum := computeChecksum(pkt[:20])
	binary.BigEndian.PutUint16(pkt[10:12], ipChecksum)

	tcpStart := 20
	binary.BigEndian.PutUint16(pkt[tcpStart:tcpStart+2], srcPort)
	binary.BigEndian.PutUint16(pkt[tcpStart+2:tcpStart+4], dstPort)
	binary.BigEndian.PutUint32(pkt[tcpStart+4:tcpStart+8], seq)
	binary.BigEndian.PutUint32(pkt[tcpStart+8:tcpStart+12], ack)
	pkt[tcpStart+12] = 0x50
	pkt[tcpStart+13] = 0x18
	binary.BigEndian.PutUint16(pkt[tcpStart+14:tcpStart+16], 65535)
	binary.BigEndian.PutUint16(pkt[tcpStart+16:tcpStart+18], 0)
	binary.BigEndian.PutUint16(pkt[tcpStart+18:tcpStart+20], 0)

	copy(pkt[tcpStart+20:], data)

	pseudoHdr := make([]byte, 12)
	copy(pseudoHdr[0:4], srcIP)
	copy(pseudoHdr[4:8], dstIP)
	pseudoHdr[8] = 0
	pseudoHdr[9] = 6
	binary.BigEndian.PutUint16(pseudoHdr[10:12], uint16(20+len(data)))

	tcpCS := tcpChecksum(pseudoHdr, pkt[tcpStart:])
	binary.BigEndian.PutUint16(pkt[tcpStart+16:tcpStart+18], tcpCS)

	return pkt
}

func computeChecksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i < len(data)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i:]))
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}

func tcpChecksum(pseudoHdr, tcpSegment []byte) uint16 {
	var sum uint32
	for i := 0; i < len(pseudoHdr)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(pseudoHdr[i:]))
	}
	if len(pseudoHdr)%2 == 1 {
		sum += uint32(pseudoHdr[len(pseudoHdr)-1]) << 8
	}
	for i := 0; i < len(tcpSegment)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(tcpSegment[i:]))
	}
	if len(tcpSegment)%2 == 1 {
		sum += uint32(tcpSegment[len(tcpSegment)-1]) << 8
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}
