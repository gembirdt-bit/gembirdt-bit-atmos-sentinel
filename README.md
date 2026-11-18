# Atmos Sentinel

Android aplikacija za proveru zdravlja HDD i SSD diskova priključenih preko USB OTG kabla — bez root-a.

## Mogućnosti
- SMART parametri: zdravlje (%), temperatura, vreme rada, broj uključivanja
- Realocirani, nestabilni i nepopravljivi sektori, CRC greške
- Preostali vek SSD-a
- SATA HDD/SSD (USB kućišta sa SAT podrškom: JMicron, ASMedia, Realtek, VIA…)
- NVMe SSD u kućištima sa Realtek RTL9210/9220 i ASMedia ASM236x čipom
- Brzi i pun test čitanja površine (ne briše podatke) sa mapom diska
- Test upisa + brzi format (FAT32) — briše sve podatke
- Deljenje izveštaja

## Instalacija
**Releases → AtmosSentinel.apk** — preuzmi i instaliraj na telefon.

## Napomene
- 2.5" diskovima nekad treba dodatno napajanje (Y kabl ili hub sa napajanjem), 3.5" diskovi ga uvek traže.
- Neka jeftina USB kućišta ne prosleđuju SMART podatke; testovi čitanja i format i dalje rade.
- Posle provere izvuci i ponovo uključi disk da bi ga telefon ponovo video kao memoriju.
