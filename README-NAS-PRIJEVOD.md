# SmartTube Naš prijevod

Lični Android TV build SmartTubea s engleskim transkriptom i bosanskim titlovima. Izvor: [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube), commit `6f9b5f2c82f280027c690967a71afb7022effac7`, verzija `32.64-nas-2`. Repozitorij: `C:\smarttube-prijevod`.

## Instalacija

APK: **`releases\SmartTube-Nas-Prijevod.apk`**. Paket: `app.smarttube.prijevod`. Instalira se kao **SmartTube Naš prijevod**, uz službeni SmartTube. Koristi ARM 32/64-bit ili x86 Android TV. Postojeći SmartTube i njegovi podaci ostaju dostupni. Verzija `32.64-nas-2` ažurira raniji vlastiti build bez brisanja prijevoda.

**Ovaj lični APK sadrži tvoj OpenAI API ključ. Ključ se može izvući iz APK-a; Git ignorisanje, javni link i Downloader kod ne štite APK. Namijenjen je samo tvojim uređajima.**

Direktno preuzimanje u Downloaderu: `https://github.com/admir-live/smarttube-prijevod/releases/latest/download/SmartTube-Nas-Prijevod.apk`. Link uvijek preuzima posljednji objavljeni vlastiti build.

1. Prenesi APK na TV preko USB-a ili aplikacije za prijenos fajlova.
2. Na TV-u dozvoli instalaciju iz odabranog file managera, pa otvori APK.
3. Pokreni **SmartTube Naš prijevod**. Po želji prijavi YouTube nalog u ovoj zasebnoj aplikaciji.

Ako koristiš ADB, omogući debugging na TV-u i odobri vezu s računarom:

```powershell
C:\smarttube-tools\android-sdk\platform-tools\adb.exe connect IP_ADRESA_TV_A:5555
C:\smarttube-tools\android-sdk\platform-tools\adb.exe install -r C:\smarttube-prijevod\releases\SmartTube-Nas-Prijevod.apk
```

Port može biti drugačiji kada Android TV koristi Wireless debugging i pairing. Adresu i port pročitaj na TV-u.

## Prvi prijevod

1. Otvori snimljeni YouTube video koji ima originalni engleski transkript.
2. U playeru **drži dugme CC/titlovi** da otvoriš meni titlova. Pri prvom uključivanju kratki klik također može otvoriti ovaj meni.
3. Odaberi **Naš prijevod — bosanski titlovi → Prevedi video**.
4. Ključ je unaprijed podešen. Unos nije potreban ni nakon ponovnog pokretanja aplikacije ili TV-a.
5. Prvi završeni titlovi prikazuju se tokom prevođenja. Meni možeš zatvoriti i nastaviti gledati.

Zadržane su SmartTube postavke veličine, položaja i stila titlova. Kratki klik na CC isključuje aktivne bosanske titlove. Ponovo ih prikaži kroz **Naš prijevod → Prikaži bosanske titlove**. Izbor običnog jezika titlova također isključuje bosanski prikaz.

**Zaustavi prijevod** prekida daljnju obradu. **Nastavi prijevod** obrađuje nedovršene redove. Promjena videa ili zatvaranje playera prekida posao. Već poslani zahtjev može biti naplaćen i nakon prekida.

## Prevodilac i čuvanje

Model je `gpt-6-luna`, isti kao u `C:\nas-prijevod`. Jezičke upute i priprema konteksta prenesene su iz verzije ekstenzije 0.3.2. Svaki dio od 30 minuta dobija pregled i rječnik, pa se prevodi u grupama od najviše 32 titla i približno 4.000 izvornih znakova. Prethodni dio i susjedni redovi služe za kontinuitet. Terminologija čuva npr. marketinški **funnel**.

Prevodilac koristi [Responses API i Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses). [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna) mora biti dostupan tvom API računu. **API potrošnja se posebno naplaćuje.** Meni prikazuje primljeni ukupni broj tokena; konačna potrošnja vidi se na API računu.

Klik na prijevod šalje naslov, transkript trenutnog dijela i kontekst direktno na `api.openai.com`, s `store: false`. Prijevod koristi YouTubeov postojeći transkript. Nema novog posredničkog servera. Postojeće SmartTube mrežne funkcije nastavljaju raditi prema izvornom projektu.

Ključ je u privatnom konfiguracijskom fajlu **`%USERPROFILE%\.smarttube-prijevod\openai.properties`**, izvan repozitorija. Fajl je dostupan samo trenutnom Windows korisniku. Sadrži postavku `apiKey`; njegov sadržaj ne upisuje se u Git.

Tokom builda Gradle ugrađuje ključ u ovaj lični APK. Aplikacija ga automatski učitava prije prvog prijevoda. Meni prikazuje **API ključ je unaprijed podešen**, bez prikazivanja samog ključa. Ključ ne ulazi u sačuvane transkripte/prijevode. Za promjenu ključa promijeni privatnu konfiguraciju i napravi novi APK. Ako je ključ opozvan ili nema pristup modelu/kredit, aplikacija prijavi API grešku.

Prevođenje se i dalje pokreće klikom **Prevedi video**; samo otvaranje aplikacije ne šalje plaćeni zahtjev. Build bez privatne konfiguracije zadržava raniji ručni unos ključa.

Transkript, kontekst i prijevodi čuvaju se nakon pripreme i svake grupe titlova u privatnom `no_backup/bosnian-subtitles` folderu aplikacije. Ponovno otvaranje videa učitava prijevod bez API poziva. Pohrana je približno ograničena na 30 MB; najstariji prijevodi se uklanjaju kada je potrebno. Jedan sačuvani video/odgovor ima limit 2 MB i 15.000 redova.

Vlastiti build se ažurira instalacijom novog vlastitog APK-a. Ugrađeni updater službenog SmartTubea ne pokreće se za ovaj paket.

## Ponovni build na ovom računaru

Pripremljen je JDK 11 u `C:\smarttube-tools\jdk11` i Android SDK u `C:\smarttube-tools\android-sdk`. `local.properties` upućuje na SDK. Java 17 koja je ranije instalirana nije promijenjena.

```powershell
cd C:\smarttube-prijevod
powershell -ExecutionPolicy Bypass -File .\build-prijevod.ps1
```

Skripta provjeri privatnu OpenAI konfiguraciju, pokrene nove testove, napravi potpisani release i kopira universal APK u `releases`. Sačuvaj privatni signing keystore `%USERPROFILE%\.smarttube-prijevod\release.jks` i lokalni `keystore.properties`; isti ključ potreban je za buduća ažuriranja bez brisanja aplikacije. Oba su izvan Git praćenja i dostupna samo trenutnom Windows korisniku. Signing ključ aplikacije i OpenAI API ključ su različite stvari.

Iz čiste kopije na drugom računaru prvo inicijalizuj submodule (`git submodule update --init --recursive`), pripremi JDK 11 i Android SDK 34 s build tools 30.0.3, te upiši SDK u `local.properties`. Debug build bez signing postavki:

```powershell
.\gradlew.bat :common:testStfdroidDebugUnitTest --tests '*BosnianSubtitle*Test' :smarttubetv:assembleStfdroidDebug
```

## Granice prve verzije

Prikaz i prijevod nisu još provjereni na fizičkom TV-u ni stvarnim OpenAI pozivom. Android build i 13 automatizovanih provjera pokrivaju parser transkripta, vremena titlova, izbor engleskog izvora, API format i odbijanja, cache, promjenu videa, obične titlove, zatvaranje playera i dostupnost konfigurisanog ključa prije ručnog unosa. Ne potvrđuju ispravnost API ključa, kvalitet jezika ili YouTube dostupnost svakog transkripta.

Release build prolazi uz upozorenje izvornog projekta: dodatne WorkManager lint provjere traže Java 17, dok APK gradimo s preporučenim JDK 11. Te dodatne lint provjere nisu izvršene. Osnovni release lint i build zadaci završavaju se uspješno.

Podržani su originalni engleski autorski i automatski transkripti. YouTubeov prijevod drugog jezika na engleski se preskače. Video bez dostupnog engleskog transkripta i aktivan prijenos uživo ne mogu se prevesti. Zvuk i govor se ne prevode; video zadržava originalni audio.

Dugi engleski redovi dijele se uz procijenjen raspored riječi u vremenu. Ovo nije precizna sinhronizacija svake riječi. Sažetak prethodnog dijela i susjedni redovi su preneseni; izbor kratkih odlomaka iz cijelog videa iz Chrome ekstenzije nije prenesen u prvu TV verziju. SRT/VTT uvoz/izvoz i panel za čitanje transkripta nisu uključeni.

Izmjene su na lokalnoj grani `nas-prijevod`; online fork nije kreiran. Izvorna MIT licenca SmartTubea ostaje u `LICENSE`.
