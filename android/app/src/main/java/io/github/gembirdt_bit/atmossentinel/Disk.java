package io.github.gembirdt_bit.atmossentinel;

import java.io.IOException;

/** SCSI komande + ATA (SAT) i NVMe prolaz kroz USB most. */
final class Disk {

    static final int T_UNKNOWN = 0, T_ATA = 1, T_NVME = 2;
    static final int SAT_NONE = 0, SAT16 = 16, SAT12 = 12;
    static final int NV_NONE = 0, NV_REALTEK = 1, NV_ASMEDIA = 2;

    final UsbBot bot;
    final int vid, pid;

    String vendor = "", product = "", revision = "";
    long sectors;          // broj logičkih blokova
    int blockSize = 512;

    int type = T_UNKNOWN;
    int sat = SAT_NONE;
    int nvme = NV_NONE;
    String bridge = "";

    byte[] ataIdentify;    // 512 B
    byte[] nvmeIdentify;   // 4096 B

    Disk(UsbBot bot, int vid, int pid) {
        this.bot = bot;
        this.vid = vid;
        this.pid = pid;
        bridge = bridgeName(vid);
    }

    static String bridgeName(int vid) {
        switch (vid) {
            case 0x152d: return "JMicron";
            case 0x174c: return "ASMedia";
            case 0x0bda: return "Realtek";
            case 0x2109: return "VIA Labs";
            case 0x1058: return "Western Digital";
            case 0x0bc2: return "Seagate";
            case 0x04e8: return "Samsung";
            case 0x0480: return "Toshiba";
            case 0x059f: return "LaCie";
            case 0x7825: return "Other World Computing";
            case 0x067b: return "Prolific";
            case 0x04b4: return "Cypress";
            case 0x1f75: return "Innostor";
            case 0x13fd: return "Initio";
            case 0x0080: return "Generic";
            default: return "";
        }
    }

    // ---------- osnovne SCSI komande ----------

    void init() throws IOException {
        int maxLun = bot.maxLun();
        bot.lun = 0;
        // Disk se možda tek okreće — nekoliko pokušaja
        for (int i = 0; i < 8; i++) {
            UsbBot.Result r = bot.command(new byte[6], null, 0, UsbBot.DIR_NONE, 5000);
            if (r.ok()) break;
            sleep(700);
        }
        byte[] inq = new byte[36];
        byte[] cdb = new byte[6];
        cdb[0] = 0x12;
        cdb[4] = 36;
        UsbBot.Result r = bot.command(cdb, inq, 36, UsbBot.DIR_IN, 5000);
        if (r.ok()) {
            vendor = ascii(inq, 8, 8);
            product = ascii(inq, 16, 16);
            revision = ascii(inq, 32, 4);
        }
        readCapacity();
        if (maxLun > 0 && sectors == 0) {
            // Čitač kartica sa više slotova — probaj ostale
            for (int l = 1; l <= maxLun && sectors == 0; l++) { bot.lun = l; readCapacity(); }
        }
    }

    void readCapacity() throws IOException {
        byte[] b = new byte[8];
        byte[] cdb = new byte[10];
        cdb[0] = 0x25;
        UsbBot.Result r = bot.command(cdb, b, 8, UsbBot.DIR_IN, 5000);
        if (!r.ok()) {
            sleep(500);
            r = bot.command(cdb, b, 8, UsbBot.DIR_IN, 5000);
        }
        if (!r.ok()) return;
        long last = be32(b, 0);
        blockSize = (int) be32(b, 4);
        if (last == 0xFFFFFFFFL) {
            byte[] b16 = new byte[32];
            byte[] c16 = new byte[16];
            c16[0] = (byte) 0x9E;
            c16[1] = 0x10;
            c16[13] = 32;
            UsbBot.Result r2 = bot.command(c16, b16, 32, UsbBot.DIR_IN, 5000);
            if (r2.ok()) {
                last = (be32(b16, 0) << 32) | be32(b16, 4);
                blockSize = (int) be32(b16, 8);
            }
        }
        if (blockSize <= 0 || blockSize > 65536) blockSize = 512;
        sectors = last + 1;
    }

    long capacityBytes() { return sectors * (long) blockSize; }

    boolean read(long lba, int count, byte[] buf) throws IOException {
        return rw(false, lba, count, buf);
    }

    boolean write(long lba, int count, byte[] buf) throws IOException {
        return rw(true, lba, count, buf);
    }

    UsbBot.Result last;

    private boolean rw(boolean wr, long lba, int count, byte[] buf) throws IOException {
        byte[] cdb;
        if (lba + count <= 0xFFFFFFFFL && count <= 0xFFFF) {
            cdb = new byte[10];
            cdb[0] = (byte) (wr ? 0x2A : 0x28);
            putBe32(cdb, 2, lba);
            cdb[7] = (byte) (count >> 8);
            cdb[8] = (byte) count;
        } else {
            cdb = new byte[16];
            cdb[0] = (byte) (wr ? 0x8A : 0x88);
            putBe32(cdb, 2, lba >>> 32);
            putBe32(cdb, 6, lba);
            putBe32(cdb, 10, count);
        }
        int len = count * blockSize;
        last = bot.command(cdb, buf, len, wr ? UsbBot.DIR_OUT : UsbBot.DIR_IN, 30000);
        return last.ok() && last.transferred == len;
    }

    void syncCache() {
        try {
            byte[] cdb = new byte[10];
            cdb[0] = 0x35;
            bot.command(cdb, null, 0, UsbBot.DIR_NONE, 60000);
        } catch (IOException ignored) { }
    }

    // ---------- ATA preko SAT (SCSI/ATA Translation) ----------

    /** PIO data-in ATA komanda (512 B). Vraća null ako most ne prosleđuje. */
    private byte[] ataIn(int mode, int command, int features, int lbaMid, int lbaHigh) throws IOException {
        byte[] data = new byte[512];
        byte[] cdb;
        if (mode == SAT16) {
            cdb = new byte[16];
            cdb[0] = (byte) 0x85;
            cdb[1] = (byte) (4 << 1);      // PIO data-in
            cdb[2] = 0x0E;                 // t_dir=in, byt_blok=1, t_length=sector count
            cdb[4] = (byte) features;
            cdb[6] = 1;
            cdb[10] = (byte) lbaMid;
            cdb[12] = (byte) lbaHigh;
            cdb[14] = (byte) command;
        } else {
            cdb = new byte[12];
            cdb[0] = (byte) 0xA1;
            cdb[1] = (byte) (4 << 1);
            cdb[2] = 0x0E;
            cdb[3] = (byte) features;
            cdb[4] = 1;
            cdb[6] = (byte) lbaMid;
            cdb[7] = (byte) lbaHigh;
            cdb[9] = (byte) command;
        }
        UsbBot.Result r = bot.command(cdb, data, 512, UsbBot.DIR_IN, 15000);
        if (!r.ok() || r.transferred < 512) return null;
        return data;
    }

    /** Proverava 512 B blok sa ATA kontrolnim zbirom (bajt 511). */
    static boolean validAtaBlock(byte[] b, boolean identify) {
        if (b == null) return false;
        boolean nz = false;
        for (byte x : b) if (x != 0) { nz = true; break; }
        if (!nz) return false;
        if (identify) {
            if ((b[510] & 0xFF) == 0xA5) {
                int s = 0;
                for (byte x : b) s += x & 0xFF;
                return (s & 0xFF) == 0;
            }
            String m = ataString(b, 27, 20);
            return m.length() > 0 && printable(m);
        }
        int s = 0;
        for (byte x : b) s += x & 0xFF;
        return (s & 0xFF) == 0;
    }

    /** Pokušava da pročita ATA IDENTIFY; određuje koji SAT režim radi. */
    boolean probeAta() throws IOException {
        int[] modes = {SAT16, SAT12};
        for (int m : modes) {
            byte[] id = ataIn(m, 0xEC, 0, 0, 0);
            if (validAtaBlock(id, true)) {
                sat = m;
                ataIdentify = id;
                type = T_ATA;
                return true;
            }
        }
        return false;
    }

    byte[] smartData() throws IOException {
        byte[] d = ataIn(sat, 0xB0, 0xD0, 0x4F, 0xC2);
        return validAtaBlock(d, false) ? d : null;
    }

    byte[] smartThresholds() throws IOException {
        byte[] d = ataIn(sat, 0xB0, 0xD1, 0x4F, 0xC2);
        return validAtaBlock(d, false) ? d : null;
    }

    /** Uključuje SMART ako je isključen (SMART ENABLE OPERATIONS). */
    void smartEnable() {
        try {
            byte[] cdb = new byte[16];
            cdb[0] = (byte) 0x85;
            cdb[1] = (byte) (3 << 1);  // non-data
            cdb[2] = 0x00;
            cdb[4] = (byte) 0xD8;
            cdb[10] = 0x4F;
            cdb[12] = (byte) 0xC2;
            cdb[14] = (byte) 0xB0;
            if (sat == SAT12) {
                cdb = new byte[12];
                cdb[0] = (byte) 0xA1;
                cdb[1] = (byte) (3 << 1);
                cdb[3] = (byte) 0xD8;
                cdb[6] = 0x4F;
                cdb[7] = (byte) 0xC2;
                cdb[9] = (byte) 0xB0;
            }
            bot.command(cdb, null, 0, UsbBot.DIR_NONE, 10000);
        } catch (IOException ignored) { }
    }

    // ---------- NVMe preko USB mostova (Realtek RTL9210/9220, ASMedia ASM236x) ----------

    private byte[] nvmeAdmin(int mode, int opcode, int cdw10, int size) throws IOException {
        byte[] data = new byte[size];
        byte[] cdb = new byte[16];
        if (mode == NV_REALTEK) {
            cdb[0] = (byte) 0xE4;
            cdb[1] = (byte) size;
            cdb[2] = (byte) (size >> 8);
            cdb[3] = (byte) opcode;
            cdb[4] = (byte) cdw10;
        } else {
            cdb[0] = (byte) 0xE6;
            cdb[1] = (byte) opcode;
            cdb[3] = (byte) cdw10;
            cdb[7] = (byte) (cdw10 >>> 16);
        }
        UsbBot.Result r = bot.command(cdb, data, size, UsbBot.DIR_IN, 15000);
        if (!r.ok() || r.transferred < Math.min(size, 512)) return null;
        return data;
    }

    private static boolean validNvmeIdentify(byte[] b) {
        if (b == null) return false;
        String model = ascii(b, 24, 40);
        String serial = ascii(b, 4, 20);
        return model.length() >= 3 && printable(model) && printable(serial);
    }

    boolean probeNvme() throws IOException {
        int[] order;
        if (vid == 0x0bda) order = new int[]{NV_REALTEK};
        else if (vid == 0x174c) order = new int[]{NV_ASMEDIA};
        else order = new int[]{NV_REALTEK, NV_ASMEDIA};
        for (int m : order) {
            byte[] id = nvmeAdmin(m, 0x06, 0x01, 4096);
            if (validNvmeIdentify(id)) {
                nvme = m;
                nvmeIdentify = id;
                type = T_NVME;
                return true;
            }
        }
        return false;
    }

    byte[] nvmeSmartLog() throws IOException {
        // Get Log Page, LID 02h, 512 B → NUMD-1 = 127
        return nvmeAdmin(nvme, 0x02, 0x02 | (127 << 16), 512);
    }

    // ---------- pomoćne ----------

    static String ataString(byte[] b, int word, int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            int o = (word + i) * 2;
            sb.append((char) (b[o + 1] & 0xFF));
            sb.append((char) (b[o] & 0xFF));
        }
        return sb.toString().trim();
    }

    static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && off + i < b.length; i++) {
            int c = b[off + i] & 0xFF;
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    static int ataWord(byte[] b, int w) {
        return (b[w * 2] & 0xFF) | ((b[w * 2 + 1] & 0xFF) << 8);
    }

    static long be32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    static void putBe32(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    static long le(byte[] b, int o, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
