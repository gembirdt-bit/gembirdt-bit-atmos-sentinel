package io.github.gembirdt_bit.atmossentinel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Random;

/** Brzi format: MBR + jedna FAT32 particija preko celog diska (do 2 TB kod 512 B sektora). */
final class Fat32 {

    interface Progress { void on(int pct, String msg); }

    String info = "";

    void format(Disk d, String label, Progress p) throws IOException {
        final int bps = d.blockSize;
        if (bps < 512 || bps > 4096 || (bps & (bps - 1)) != 0) throw new IOException("Nepodržana veličina sektora: " + bps);
        long disk = d.sectors;
        long start = (1024L * 1024) / bps;                  // poravnanje na 1 MiB
        long maxEnd = 0xFFFFFFFFL;                           // MBR ograničenje
        long total = Math.min(disk, maxEnd) - start;
        if (total < 65536) throw new IOException("Disk je premali za FAT32");
        boolean capped = disk > maxEnd;

        long bytes = total * bps;
        int clusterBytes;
        if (bytes <= 8L << 30) clusterBytes = 4096;
        else if (bytes <= 16L << 30) clusterBytes = 8192;
        else if (bytes <= 32L << 30) clusterBytes = 16384;
        else clusterBytes = 32768;
        if (clusterBytes < bps) clusterBytes = bps;

        int spc, reserved, fatSz;
        long clusters;
        while (true) {
            spc = clusterBytes / bps;
            reserved = 32;
            fatSz = fatSize(total, reserved, spc, bps);
            // poravnaj početak podataka na veličinu klastera
            int pad = (int) ((spc - (reserved + 2L * fatSz) % spc) % spc);
            reserved += pad;
            fatSz = fatSize(total, reserved, spc, bps);
            clusters = (total - reserved - 2L * fatSz) / spc;
            if (clusters < 0x0FFFFFF5L || clusterBytes >= 65536) break;
            clusterBytes *= 2;
        }
        if (clusters < 65525) throw new IOException("Premalo klastera za FAT32");
        if (clusters >= 0x0FFFFFF5L) throw new IOException("Disk je prevelik za FAT32");

        info = String.format(Locale.ROOT, "FAT32, klaster %d KB, %s", clusterBytes / 1024, MainActivity.size(total * bps))
                + (capped ? " (MBR ograničenje — ostatak diska nije iskorišćen)" : "");

        // 1) obriši početak diska (stare particione tabele) i kraj (rezervni GPT)
        p.on(1, "Brisanje stare particione tabele…");
        zero(d, 0, start, null);
        long tailLen = Math.min(start, disk);
        zero(d, disk - tailLen, tailLen, null);

        // 2) rezervisani sektori + FAT tabele + korenski klaster
        long fatStart = start + reserved;
        long dataStart = fatStart + 2L * fatSz;
        p.on(3, "Brisanje FAT tabela…");
        long zeroLen = (dataStart + spc) - start;
        final long zl = zeroLen;
        zero(d, start, zeroLen, done -> p.on(3 + (int) (done * 90 / zl), "Brisanje FAT tabela…"));

        // 3) boot sektor, FSInfo i rezervne kopije
        p.on(94, "Upis boot sektora…");
        int volId = new Random().nextInt();
        byte[] boot = new byte[bps];
        ByteBuffer b = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN);
        boot[0] = (byte) 0xEB; boot[1] = 0x58; boot[2] = (byte) 0x90;
        put(boot, 3, "MSWIN4.1", 8);
        b.putShort(11, (short) bps);
        boot[13] = (byte) spc;
        b.putShort(14, (short) reserved);
        boot[16] = 2;
        b.putShort(17, (short) 0);
        b.putShort(19, (short) 0);
        boot[21] = (byte) 0xF8;
        b.putShort(22, (short) 0);
        b.putShort(24, (short) 63);
        b.putShort(26, (short) 255);
        b.putInt(28, (int) start);
        b.putInt(32, (int) total);
        b.putInt(36, fatSz);
        b.putShort(40, (short) 0);
        b.putShort(42, (short) 0);
        b.putInt(44, 2);
        b.putShort(48, (short) 1);
        b.putShort(50, (short) 6);
        boot[64] = (byte) 0x80;
        boot[66] = 0x29;
        b.putInt(67, volId);
        put(boot, 71, label, 11);
        put(boot, 82, "FAT32", 8);
        boot[90] = (byte) 0xF4; boot[91] = (byte) 0xEB; boot[92] = (byte) 0xFD; // hlt; jmp $
        boot[510] = 0x55; boot[511] = (byte) 0xAA;

        byte[] fsi = new byte[bps];
        ByteBuffer f = ByteBuffer.wrap(fsi).order(ByteOrder.LITTLE_ENDIAN);
        f.putInt(0, 0x41615252);
        f.putInt(484, 0x61417272);
        f.putInt(488, (int) (clusters - 1));
        f.putInt(492, 3);
        f.putInt(508, 0xAA550000);

        byte[] third = new byte[bps];
        third[510] = 0x55; third[511] = (byte) 0xAA;

        for (int base : new int[]{0, 6}) {
            wr(d, start + base, boot);
            wr(d, start + base + 1, fsi);
            wr(d, start + base + 2, third);
        }

        // 4) početni unosi u obe FAT tabele
        byte[] fat0 = new byte[bps];
        ByteBuffer fb = ByteBuffer.wrap(fat0).order(ByteOrder.LITTLE_ENDIAN);
        fb.putInt(0, 0x0FFFFFF8);
        fb.putInt(4, 0x0FFFFFFF);
        fb.putInt(8, 0x0FFFFFFF);   // korenski direktorijum (klaster 2)
        wr(d, fatStart, fat0);
        wr(d, fatStart + fatSz, fat0);

        // 5) oznaka diska u korenskom direktorijumu
        byte[] root = new byte[bps];
        put(root, 0, label, 11);
        root[11] = 0x08;
        wr(d, dataStart, root);

        // 6) MBR
        p.on(97, "Upis particione tabele…");
        byte[] mbr = new byte[bps];
        ByteBuffer m = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN);
        int e = 446;
        mbr[e] = 0x00;
        mbr[e + 1] = (byte) 0xFE; mbr[e + 2] = (byte) 0xFF; mbr[e + 3] = (byte) 0xFF;
        mbr[e + 4] = 0x0C;     // FAT32 LBA
        mbr[e + 5] = (byte) 0xFE; mbr[e + 6] = (byte) 0xFF; mbr[e + 7] = (byte) 0xFF;
        m.putInt(e + 8, (int) start);
        m.putInt(e + 12, (int) total);
        m.putInt(440, volId);
        mbr[510] = 0x55; mbr[511] = (byte) 0xAA;
        wr(d, 0, mbr);

        d.syncCache();
        p.on(100, "Gotovo");
    }

    private static int fatSize(long total, int reserved, int spc, int bps) {
        long perSector = bps / 4;
        long fat = 1;
        for (int i = 0; i < 50; i++) {
            long cl = (total - reserved - 2 * fat) / spc;
            long need = (cl + 2 + perSector - 1) / perSector;
            if (need <= fat) break;
            fat = need;
        }
        return (int) fat;
    }

    interface Done { void on(long done); }

    static void zero(Disk d, long lba, long count, Done cb) throws IOException {
        int per = Math.max(1, (512 * 1024) / d.blockSize);
        byte[] z = new byte[per * d.blockSize];
        long done = 0;
        while (done < count) {
            int n = (int) Math.min(per, count - done);
            if (!d.write(lba + done, n, z)) throw writeErr(d, lba + done);
            done += n;
            if (cb != null) cb.on(done);
        }
    }

    private static void wr(Disk d, long lba, byte[] sector) throws IOException {
        if (!d.write(lba, 1, sector)) throw writeErr(d, lba);
    }

    static IOException writeErr(Disk d, long lba) {
        UsbBot.Result r = d.last;
        if (r != null && r.senseKey == 7) return new IOException("Disk je zaštićen od upisa");
        return new IOException("Greška pri upisu na sektoru " + lba);
    }

    private static void put(byte[] b, int off, String s, int len) {
        String t = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < len; i++) {
            char c = i < t.length() ? t.charAt(i) : ' ';
            if (c < 0x20 || c > 0x7E) c = '_';
            b[off + i] = (byte) c;
        }
    }
}
