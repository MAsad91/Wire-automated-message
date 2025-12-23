# Wire Auto Messenger

En inbyggd Android-applikation som automatiskt skickar meddelanden till alla dina Wire-kontakter med stöd för schemaläggning.

## Funktioner

- ✅ **Automatiskt meddelandeskickning**: Skicka samma meddelande till alla dina Wire-kontakter med ett klick
- ✅ **Schemalagd skickning**: Skicka automatiskt meddelanden var 3:e dag
- ✅ **Enkelt gränssnitt**: Lättanvänt gränssnitt för att skriva meddelanden och hantera scheman
- ✅ **Fristående APK**: Inga externa beroenden eller teknisk konfiguration krävs
- ✅ **Säker**: Körs helt på din enhet med Android's Accessibility Service

## Krav

- Android-enhet med Android 7.0 (API 24) eller högre
- Wire-appen installerad och konfigurerad på din enhet
- Minst 500+ kontakter i Wire (enligt kundkrav)

## Installationsinstruktioner

### Steg 1: Aktivera Okända Källor

1. Gå till **Inställningar** på din Android-enhet
2. Navigera till **Säkerhet** eller **Appar** (beroende på din Android-version)
3. Aktivera **Installera från okända källor** eller **Tillåt från denna källa**

### Steg 2: Installera APK-filen

1. Överför `app-release.apk`-filen till din Android-enhet
2. Öppna APK-filen med en filhanterare
3. Tryck på **Installera** när du uppmanas
4. Vänta tills installationen är klar

### Steg 3: Aktivera Accessibility Service

1. Öppna **Wire Auto Messenger**-appen
2. Tryck på knappen **"Aktivera Accessibility Service"**
3. Du kommer att omdirigeras till Tillgänglighetsinställningar
4. Hitta **"Wire Auto Messenger"** i listan
5. Växla den **PÅ**
6. Bekräfta behörighetsdialogen
7. Återvänd till appen

**Viktigt**: Appen kräver Accessibility Service-behörighet för att automatisera meddelandeskickning i Wire.

## Användarguide

### Skicka Meddelanden Nu

1. Öppna **Wire Auto Messenger**
2. Ange ditt meddelande i textfältet
3. Se till att Accessibility Service är aktiverad (grön bock)
4. Tryck på knappen **"Skicka Nu"**
5. Appen kommer automatiskt att:
   - Öppna Wire-appen
   - Navigera genom alla kontakter
   - Skicka ditt meddelande till varje kontakt
   - Visa förlopp i notifikationer

### Konfigurera Schemalagd Skickning

1. Öppna **Wire Auto Messenger**
2. Ange ditt meddelande i textfältet
3. Växla **"Skicka var 3:e dag"** till PÅ
4. Appen kommer automatiskt att skicka ditt meddelande var 3:e dag
5. Du kan se nästa skicktid nedanför växeln

### Inaktivera Schemalagd Skickning

1. Öppna **Wire Auto Messenger**
2. Växla **"Skicka var 3:e dag"** till AV
3. Schemalagd skickning kommer att inaktiveras

## Hur Det Fungerar

Appen använder Android's Accessibility Service API för att:
- Automatiskt navigera Wire-appens gränssnitt
- Hitta och klicka på kontakter
- Skriva meddelanden i konversationsfönster
- Skicka meddelanden genom att klicka på skicka-knappen
- Återvända till kontaktlistan och upprepa

**Autentisering**: Appen använder din befintliga Wire-appinstallation. Ingen separat autentisering krävs - den fungerar med ditt redan inloggade Wire-konto.

## Tekniska Detaljer

### Bibliotek & Ramverk Som Används

- **Kotlin**: Modernspråk för Android-utveckling
- **AndroidX**: Senaste Android-stödbiblioteken
- **WorkManager**: För pålitlig bakgrundsschemaläggning
- **Accessibility Service API**: För UI-automatisering
- **Material Design Components**: För modernt gränssnitt

### Arkitektur

- **MainActivity**: Användargränssnitt för meddelandekomposition och inställningar
- **WireAutomationService**: Accessibility-tjänst som hanterar automatisering
- **MessageSendingWorker**: Bakgrundsarbetare för schemalagd skickning
- **BootReceiver**: Återställer schemalagd skickning efter enhetsomstart

## Felsökning

### Accessibility Service Fungerar Inte

- Se till att tjänsten är aktiverad i Android-inställningar
- Starta om appen efter att ha aktiverat tjänsten
- Kontrollera att Wire-appen är installerad och tillgänglig

### Meddelanden Skickas Inte

- Verifiera att Wire-appen är installerad och att du är inloggad
- Kontrollera att du har kontakter i Wire
- Se till att meddelandefältet inte är tomt
- Försök skicka till en mindre grupp först för att testa

### Schemalagd Skickning Fungerar Inte

- Se till att schemaväxeln är aktiverad
- Kontrollera att Accessibility Service är aktiverad
- Verifiera att meddelandet är sparat (inte tomt)
- Starta om enheten och kontrollera igen

### Appen Kraschar eller Fryser

- Tvinga stäng appen och öppna igen
- Starta om din enhet
- Aktivera om Accessibility Service
- Avinstallera och installera om appen

## Integritet & Säkerhet

- All automatisering sker lokalt på din enhet
- Inga data skickas till externa servrar
- Meddelanden skickas genom din befintliga Wire-app
- Appen har endast tillgång till Wire-appens UI-element (ingen åtkomst till personuppgifter)

## Begränsningar

- Kräver Accessibility Service-behörighet (Android-säkerhetskrav)
- Skickning till 500+ kontakter kan ta 30-60 minuter
- Ändringar i Wire-appens UI kan kräva appuppdateringar
- Enheten måste vara upplåst under meddelandeskickning

## Support

För problem eller frågor:
1. Kontrollera avsnittet Felsökning ovan
2. Se till att alla krav är uppfyllda
3. Verifiera att Accessibility Service är korrekt aktiverad

## Versionshistorik

- **v1.0.0** (Första Utgåvan)
  - Grundläggande meddelandeskickning till alla kontakter
  - Schemalagd skickning var 3:e dag
  - Enkelt användargränssnitt

## Licens

Denna applikation är utvecklad för specifika kundkrav. Alla rättigheter förbehållna.

---

**Obs**: Denna app automatiserar interaktioner med Wire-meddelandeappen. Se till att du följer Wire's användarvillkor när du använder automatiseringsfunktioner.

