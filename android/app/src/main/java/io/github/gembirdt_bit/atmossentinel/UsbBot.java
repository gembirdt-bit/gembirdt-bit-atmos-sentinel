package io.github.gembirdt_bit.atmossentinel;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * USB Mass Storage "Bulk-Only Transport" (BOT): šalje SCSI komande direktno disku
 * preko USB Host API-ja, bez root-a.
 */
final class UsbBot {

    static final int DIR_NONE = 0, DIR_IN = 1, DIR_OUT = 2;

    static final class Result {
        int status = -1;          // 0 = OK, 1 = komanda odbijena, 2 = greška faze
        int transferred;
        int senseKey = -1, asc = -1, ascq = -1;

        boolean ok() { return status == 0; }
    }

    private final UsbDeviceConnection conn;
    private final UsbInterface itf;
    private final UsbEndpoint epIn, epOut;
    private final int chunk;
    private int tag = 1;
    int lun = 0;

    UsbBot(UsbDeviceConnection conn, UsbInterface itf) throws IOException {
        this.conn = conn;
        this.itf = itf;
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < itf.getEndpointCount(); i++) {
            UsbEndpoint e = itf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) { if (in == null) in = e; }
            else if (out == null) out = e;
        }
        if (in == null || out == null) throw new IOException("Uređaj nema bulk krajnje tačke");
        epIn = in;
        epOut = out;
        // Do Androida 9 jedan bulkTransfer može najviše 16 KB
        chunk = Build.VERSION.SDK_INT >= 28 ? 256 * 1024 : 16 * 1024;
    }

    /** Izvršava SCSI komandu. data može biti null za komande bez podataka. */
    synchronized Result command(byte[] cdb, byte[] data, int len, int dir, int timeoutMs) throws IOException {
        Result r = new Result();
        int myTag = tag++;
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(0x43425355);           // "USBC"
        cbw.putInt(myTag);
        cbw.putInt(dir == DIR_NONE ? 0 : len);
        cbw.put((byte) (dir == DIR_IN ? 0x80 : 0x00));
        cbw.put((byte) lun);
        cbw.put((byte) cdb.length);
        cbw.put(cdb);
        byte[] cbwB = cbw.array();

        int w = conn.bulkTransfer(epOut, cbwB, 31, 5000);
        if (w != 31) {
            resetRecovery();
            w = conn.bulkTransfer(epOut, cbwB, 31, 5000);
            if (w != 31) throw new IOException("Disk ne odgovara (CBW)");
        }

        byte[] csw = null;
        if (dir == DIR_IN && len > 0) {
            int off = 0;
            while (off < len) {
                int n = Math.min(chunk, len - off);
                int got = conn.bulkTransfer(epIn, data, off, n, timeoutMs);
                if (got < 0) { clearHalt(epIn); break; }
                // Uređaj je poslao CSW umesto podataka (kraći odgovor)
                if (got == 13 && isCsw(data, off, myTag)) {
                    csw = new byte[13];
                    System.arraycopy(data, off, csw, 0, 13);
                    break;
                }
                off += got;
                if (got < n) break;
            }
            r.transferred = off;
        } else if (dir == DIR_OUT && len > 0) {
            int off = 0;
            while (off < len) {
                int n = Math.min(chunk, len - off);
                int put = conn.bulkTransfer(epOut, data, off, n, timeoutMs);
                if (put < 0) { clearHalt(epOut); break; }
                off += put;
                if (put < n) break;
            }
            r.transferred = off;
        }

        if (csw == null) {
            csw = new byte[13];
            int got = conn.bulkTransfer(epIn, csw, 13, timeoutMs);
            if (got != 13) {
                clearHalt(epIn);
                got = conn.bulkTransfer(epIn, csw, 13, timeoutMs);
            }
            if (got != 13 || !isCsw(csw, 0, myTag)) {
                resetRecovery();
                r.status = 2;
                return r;
            }
        }
        r.status = csw[12] & 0xFF;
        if (r.status == 2) {
            resetRecovery();
        } else if (r.status == 1) {
            requestSense(r);
        }
        return r;
    }

    private void requestSense(Result r) {
        try {
            byte[] cdb = new byte[6];
            cdb[0] = 0x03;
            cdb[4] = 18;
            byte[] s = new byte[18];
            Result x = commandNoSense(cdb, s, 18);
            if (x.status == 0 && x.transferred >= 14) {
                int code = s[0] & 0x7F;
                if (code == 0x72 || code == 0x73) {
                    r.senseKey = s[1] & 0x0F; r.asc = s[2] & 0xFF; r.ascq = s[3] & 0xFF;
                } else {
                    r.senseKey = s[2] & 0x0F; r.asc = s[12] & 0xFF; r.ascq = s[13] & 0xFF;
                }
            }
        } catch (IOException ignored) { }
    }

    private Result commandNoSense(byte[] cdb, byte[] data, int len) throws IOException {
        Result r = new Result();
        int myTag = tag++;
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(0x43425355).putInt(myTag).putInt(len);
        cbw.put((byte) 0x80).put((byte) lun).put((byte) cdb.length).put(cdb);
        if (conn.bulkTransfer(epOut, cbw.array(), 31, 5000) != 31) throw new IOException("CBW");
        int got = conn.bulkTransfer(epIn, data, len, 5000);
        byte[] csw;
        if (got == 13 && isCsw(data, 0, myTag)) {
            csw = data.clone();
            got = 0;
        } else {
            if (got < 0) { clearHalt(epIn); got = 0; }
            csw = new byte[13];
            if (conn.bulkTransfer(epIn, csw, 13, 5000) != 13) { clearHalt(epIn); conn.bulkTransfer(epIn, csw, 13, 5000); }
        }
        r.transferred = got;
        r.status = isCsw(csw, 0, myTag) ? (csw[12] & 0xFF) : 2;
        return r;
    }

    private static boolean isCsw(byte[] b, int off, int tag) {
        ByteBuffer bb = ByteBuffer.wrap(b, off, 13).order(ByteOrder.LITTLE_ENDIAN);
        return bb.getInt() == 0x53425355 && bb.getInt() == tag;
    }

    private void clearHalt(UsbEndpoint ep) {
        // CLEAR_FEATURE(ENDPOINT_HALT)
        conn.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, 2000);
    }

    void resetRecovery() {
        // Bulk-Only Mass Storage Reset
        conn.controlTransfer(0x21, 0xFF, 0, itf.getId(), null, 0, 2000);
        clearHalt(epIn);
        clearHalt(epOut);
    }

    int maxLun() {
        byte[] b = new byte[1];
        int n = conn.controlTransfer(0xA1, 0xFE, 0, itf.getId(), b, 1, 2000);
        return n == 1 ? (b[0] & 0x0F) : 0;
    }
}
