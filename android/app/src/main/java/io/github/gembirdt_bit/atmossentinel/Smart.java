package io.github.gembirdt_bit.atmossentinel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SMART atributi, procena zdravlja i opis problema. */
final class Smart {

    static final class Attr {
        int id, flags, value, worst, threshold = -1;
        long raw;
        byte[] rawBytes = new byte[6];
        boolean critical;   // bitno za zdravlje i > 0
        boolean failing;    // vrednost <= prag

        String name() { return attrName(id); }

        boolean prefail() { return (flags & 1) != 0; }
    }

    final List<Attr> attrs = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    // Sažetak
    String model = "", serial = "", firmware = "", kind = "";
    boolean ssd;
    int health = -1;           // 0..100, -1 = nepoznato
    int temperature = -1;
    long powerOnHours = -1, powerCycles = -1;
    long reallocated = -1, pending = -1, uncorrectable = -1, crc = -1, reallocEvents = -1, reportedUncorr = -1;
    int lifeLeft = -1;         // SSD preostali vek %
    long nvmeRead = -1, nvmeWritten = -1, nvmeMediaErrors = -1, nvmeUnsafe = -1, nvmeErrLog = -1;
    int nvmeSpare = -1, nvmeSpareThr = -1, nvmeUsed = -1, nvmeCritical = 0;
    boolean smartOk;           // da li su SMART podaci pročitani

    // ---------- ATA ----------

    static Smart fromAta(byte[] identify, byte[] data, byte[] thr) {
        Smart s = new Smart();
        s.model = Disk.ataString(identify, 27, 20);
        s.serial = Disk.ataString(identify, 10, 10);
        s.firmware = Disk.ataString(identify, 23, 4);
        int rot = Disk.ataWord(identify, 217);
        s.ssd = rot == 1;
        s.kind = s.ssd ? "SSD (SATA)" : (rot > 1 ? "HDD " + rot + " o/min" : "HDD (SATA)");
        if (data == null) {
            s.notes.add("Most (USB kućište) je prosledio identifikaciju diska, ali ne i SMART podatke. Moguće je da je SMART isključen na disku.");
            return s;
        }
        s.smartOk = true;
        for (int i = 0; i < 30; i++) {
            int o = 2 + i * 12;
            int id = data[o] & 0xFF;
            if (id == 0) continue;
            Attr a = new Attr();
            a.id = id;
            a.flags = (data[o + 1] & 0xFF) | ((data[o + 2] & 0xFF) << 8);
            a.value = data[o + 3] & 0xFF;
            a.worst = data[o + 4] & 0xFF;
            System.arraycopy(data, o + 5, a.rawBytes, 0, 6);
            a.raw = Disk.le(data, o + 5, 6);
            if (thr != null) {
                for (int j = 0; j < 30; j++) {
                    int p = 2 + j * 12;
                    if ((thr[p] & 0xFF) == id) { a.threshold = thr[p + 1] & 0xFF; break; }
                }
            }
            a.failing = a.threshold > 0 && a.value > 0 && a.value <= a.threshold && a.value != 0xFF;
            s.attrs.add(a);
        }
        s.evaluateAta();
        return s;
    }

    private Attr get(int id) {
        for (Attr a : attrs) if (a.id == id) return a;
        return null;
    }

    private static long low32(Attr a) { return a == null ? -1 : (a.raw & 0xFFFFFFFFL); }

    private static long low16(Attr a) { return a == null ? -1 : (a.raw & 0xFFFFL); }

    private void evaluateAta() {
        Attr a;
        reallocated = low32(get(5));
        pending = low32(get(197));
        uncorrectable = low32(get(198));
        crc = low32(get(199));
        reallocEvents = low32(get(196));
        reportedUncorr = low16(get(187));
        a = get(9);
        if (a != null) powerOnHours = a.raw & 0xFFFFFFFFL;
        a = get(12);
        if (a != null) powerCycles = low32(a);
        a = get(194);
        if (a == null) a = get(190);
        if (a != null) {
            int t = (int) (a.raw & 0xFF);
            if (t > 0 && t < 100) temperature = t;
            else if (a.value > 0 && a.value < 100) temperature = 100 - a.value;
        }

        // Preostali vek SSD-a (normalizovana vrednost)
        if (ssd || get(177) != null || get(231) != null || get(233) != null || get(202) != null) {
            int[] wearIds = {231, 233, 177, 202, 169, 173};
            for (int id : wearIds) {
                Attr w = get(id);
                if (w != null && w.value > 0 && w.value <= 100) {
                    if (id == 202 && !ssd) continue;
                    if (id == 233 && !ssd) continue;
                    lifeLeft = w.value;
                    break;
                }
            }
        }

        double h = 100;
        h *= f(reallocated, 60, 100);
        h *= f(pending, 50, 30);
        h *= f(uncorrectable, 50, 30);
        h *= f(reallocEvents, 20, 50);
        h *= f(reportedUncorr, 30, 30);
        if (!ssd) {
            h *= f(low32(get(10)), 20, 10);   // Spin Retry
            h *= f(low32(get(184)), 20, 10);  // End-to-End
        }
        if (lifeLeft >= 0) h = Math.min(h, lifeLeft);

        boolean anyFail = false;
        for (Attr x : attrs) {
            if (x.failing) anyFail = true;
            switch (x.id) {
                case 5: case 10: case 184: case 187: case 196: case 197: case 198:
                    x.critical = (x.raw & 0xFFFFFFFFL) > 0;
                    break;
                case 199:
                    x.critical = (x.raw & 0xFFFFFFFFL) > 0;
                    break;
                default:
                    break;
            }
        }
        if (anyFail) h = Math.min(h, 10);
        health = (int) Math.round(Math.max(0, Math.min(100, h)));

        // Opisi
        if (anyFail) {
            StringBuilder sb = new StringBuilder();
            for (Attr x : attrs) if (x.failing) sb.append(sb.length() > 0 ? ", " : "").append(x.name());
            notes.add("❗ SMART prag je dostignut (" + sb + "). Disk sam prijavljuje da otkazuje — odmah prebacite podatke.");
        }
        if (reallocated > 0)
            notes.add("Realocirani sektori: " + reallocated + ". Disk je loše sektore zamenio rezervnim. Ako broj raste, disk propada.");
        if (pending > 0)
            notes.add("Nestabilni sektori (čekaju realokaciju): " + pending + ". Ti sektori se trenutno ne mogu pouzdano pročitati — moguć gubitak podataka.");
        if (uncorrectable > 0)
            notes.add("Nepopravljivi sektori: " + uncorrectable + ". Podaci u tim sektorima su verovatno izgubljeni.");
        if (reportedUncorr > 0)
            notes.add("Prijavljene nepopravljive greške pri čitanju: " + reportedUncorr + ".");
        if (!ssd && low32(get(10)) > 0)
            notes.add("Ponovljeni pokušaji okretanja (Spin Retry): " + low32(get(10)) + " — problem sa motorom ili napajanjem.");
        if (crc > 0)
            notes.add("UDMA CRC greške: " + crc + ". Ovo obično znači loš kabl, kućište ili napajanje, a ne kvar samog diska.");
        if (lifeLeft >= 0 && lifeLeft < 30)
            notes.add("SSD je potrošio veći deo predviđenog broja upisa (preostalo oko " + lifeLeft + "%).");
        if (temperature >= 55)
            notes.add("Temperatura je visoka (" + temperature + " °C).");
        if (notes.isEmpty())
            notes.add("Nema realociranih, nestabilnih ni nepopravljivih sektora. Disk je, prema SMART podacima, ispravan.");
    }

    /** Množilac zdravlja: 1 - max·(1 - e^(-n/k)) */
    private static double f(long n, double max, double k) {
        if (n <= 0) return 1;
        return 1 - (max / 100.0) * (1 - Math.exp(-n / k));
    }

    // ---------- NVMe ----------

    static Smart fromNvme(byte[] id, byte[] log) {
        Smart s = new Smart();
        s.ssd = true;
        s.kind = "SSD (NVMe)";
        s.serial = Disk.ascii(id, 4, 20);
        s.model = Disk.ascii(id, 24, 40);
        s.firmware = Disk.ascii(id, 64, 8);
        if (log == null) {
            s.notes.add("Most je prosledio identifikaciju NVMe diska, ali ne i SMART log.");
            return s;
        }
        s.smartOk = true;
        s.nvmeCritical = log[0] & 0xFF;
        int k = (int) Disk.le(log, 1, 2);
        if (k > 200) s.temperature = k - 273;
        s.nvmeSpare = log[3] & 0xFF;
        s.nvmeSpareThr = log[4] & 0xFF;
        s.nvmeUsed = log[5] & 0xFF;
        s.nvmeRead = Disk.le(log, 32, 8);
        s.nvmeWritten = Disk.le(log, 48, 8);
        s.powerCycles = Disk.le(log, 112, 8);
        s.powerOnHours = Disk.le(log, 128, 8);
        s.nvmeUnsafe = Disk.le(log, 144, 8);
        s.nvmeMediaErrors = Disk.le(log, 160, 8);
        s.nvmeErrLog = Disk.le(log, 176, 8);
        s.lifeLeft = Math.max(0, 100 - s.nvmeUsed);

        double h = s.lifeLeft;
        if (s.nvmeMediaErrors > 0) h *= 1 - 0.5 * (1 - Math.exp(-s.nvmeMediaErrors / 20.0));
        if (s.nvmeSpareThr > 0 && s.nvmeSpare <= s.nvmeSpareThr) h = Math.min(h, 10);
        if ((s.nvmeCritical & 0x0D) != 0) h = Math.min(h, 10);
        s.health = (int) Math.round(Math.max(0, Math.min(100, h)));

        if (s.nvmeCritical != 0) {
            List<String> w = new ArrayList<>();
            if ((s.nvmeCritical & 1) != 0) w.add("rezervni prostor ispod praga");
            if ((s.nvmeCritical & 2) != 0) w.add("temperatura");
            if ((s.nvmeCritical & 4) != 0) w.add("pouzdanost umanjena");
            if ((s.nvmeCritical & 8) != 0) w.add("disk je samo za čitanje");
            if ((s.nvmeCritical & 16) != 0) w.add("rezervna memorija otkazala");
            s.notes.add("❗ Kritično upozorenje diska: " + join(w) + ".");
        }
        if (s.nvmeMediaErrors > 0)
            s.notes.add("Greške medija (nepopravljive): " + s.nvmeMediaErrors + ".");
        if (s.nvmeUsed >= 70)
            s.notes.add("Potrošeno je " + s.nvmeUsed + "% predviđenog veka upisa.");
        if (s.temperature >= 70)
            s.notes.add("Temperatura je visoka (" + s.temperature + " °C).");
        if (s.notes.isEmpty())
            s.notes.add("Nema grešaka medija ni kritičnih upozorenja. Disk je, prema SMART podacima, ispravan.");
        return s;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) sb.append(sb.length() > 0 ? ", " : "").append(x);
        return sb.toString();
    }

    static String healthLabel(int h) {
        if (h < 0) return "Nepoznato";
        if (h >= 95) return "Odlično";
        if (h >= 80) return "Dobro";
        if (h >= 60) return "Zadovoljavajuće";
        if (h >= 30) return "Loše";
        return "Kritično";
    }

    static String rawText(Attr a) {
        switch (a.id) {
            case 190: case 194: {
                int cur = (int) (a.raw & 0xFF);
                int min = (int) ((a.raw >> 16) & 0xFF), max = (int) ((a.raw >> 32) & 0xFF);
                if (min > 0 && max >= cur && max < 120) return cur + " °C (min " + min + ", max " + max + ")";
                return cur + " °C";
            }
            case 9:
                return String.valueOf(a.raw & 0xFFFFFFFFL);
            default:
                return String.valueOf(a.raw);
        }
    }

    static String hours(long h) {
        if (h < 0) return "—";
        long d = h / 24;
        if (d >= 365) return String.format(Locale.ROOT, "%d h (%.1f god.)", h, d / 365.0);
        return h + " h (" + d + " dana)";
    }

    static String attrName(int id) {
        switch (id) {
            case 1: return "Raw Read Error Rate";
            case 2: return "Throughput Performance";
            case 3: return "Spin-Up Time";
            case 4: return "Start/Stop Count";
            case 5: return "Reallocated Sectors Count";
            case 7: return "Seek Error Rate";
            case 8: return "Seek Time Performance";
            case 9: return "Power-On Hours";
            case 10: return "Spin Retry Count";
            case 11: return "Calibration Retry Count";
            case 12: return "Power Cycle Count";
            case 13: return "Soft Read Error Rate";
            case 22: return "Helium Level";
            case 160: return "Uncorrectable Sector Count";
            case 161: return "Valid Spare Blocks";
            case 163: return "Initial Bad Blocks";
            case 164: return "Total Erase Count";
            case 165: return "Max Erase Count";
            case 166: return "Min Erase Count";
            case 167: return "Average Erase Count";
            case 168: return "Max Erase Count of Spec";
            case 169: return "Remaining Life";
            case 170: return "Available Reserved Space";
            case 171: return "Program Fail Count";
            case 172: return "Erase Fail Count";
            case 173: return "Wear Leveling Count";
            case 174: return "Unexpected Power Loss";
            case 175: return "Program Fail Count (Chip)";
            case 176: return "Erase Fail Count (Chip)";
            case 177: return "Wear Leveling Count";
            case 178: return "Used Reserved Block (Chip)";
            case 179: return "Used Reserved Block (Total)";
            case 180: return "Unused Reserved Block";
            case 181: return "Program Fail Count (Total)";
            case 182: return "Erase Fail Count (Total)";
            case 183: return "Runtime Bad Block";
            case 184: return "End-to-End Error";
            case 187: return "Reported Uncorrectable Errors";
            case 188: return "Command Timeout";
            case 189: return "High Fly Writes";
            case 190: return "Airflow Temperature";
            case 191: return "G-Sense Error Rate";
            case 192: return "Power-off Retract Count";
            case 193: return "Load/Unload Cycle Count";
            case 194: return "Temperature";
            case 195: return "Hardware ECC Recovered";
            case 196: return "Reallocation Event Count";
            case 197: return "Current Pending Sector Count";
            case 198: return "Offline Uncorrectable";
            case 199: return "UDMA CRC Error Count";
            case 200: return "Multi-Zone Error Rate";
            case 201: return "Soft Read Error Rate";
            case 202: return "Percent Lifetime Remaining";
            case 206: return "Flying Height";
            case 220: return "Disk Shift";
            case 222: return "Loaded Hours";
            case 223: return "Load/Unload Retry Count";
            case 224: return "Load Friction";
            case 225: return "Load/Unload Cycle Count";
            case 226: return "Load-in Time";
            case 230: return "Drive Life Protection";
            case 231: return "SSD Life Left";
            case 232: return "Endurance Remaining";
            case 233: return "Media Wearout Indicator";
            case 234: return "Average Erase Count";
            case 235: return "Good Block Count";
            case 240: return "Head Flying Hours";
            case 241: return "Total LBAs Written";
            case 242: return "Total LBAs Read";
            case 249: return "NAND Writes (1 GiB)";
            case 250: return "Read Error Retry Rate";
            case 254: return "Free Fall Protection";
            default: return "Vendor Specific";
        }
    }
}
