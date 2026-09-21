# CYPHR — aplikacja Android

Natywna aplikacja w Kotlinie i Jetpack Compose. Czarno-biała, z logo duszka.

Zakładki:
- **Czat** — rozmowa z wybranym modelem przez `/v1/chat/completions`, dymki z animacją wejścia i duszkowy wskaźnik pisania.
- **Agenci** — lista modeli z `/v1/models`, wybór zapamiętywany na stałe.
- **Sklep** — karta płatnicza rysowana w 3D (obrót, refleks światła, chip), zakup obraca kartę i kończy znakiem potwierdzenia. Płatność jest testowa.
- **Sieć** — przeglądarka z paskiem adresu w formie pigułki (kłódka i sama domena),
  stroną startową ze skrótami, własnym paskiem narzędzi i przyciskiem „Zapytaj AI”,
  który wysyła treść otwartej strony do modelu.
- **Konto** — imię i e-mail, biała karta z aktualnym planem (Free, Plus, Pro),
  paskiem postępu do następnego progu, sumą doładowań, saldem i zużyciem w 30 dni.
- **Ustawienia** (ikona w prawym górnym rogu) — adres API, strona startowa przeglądarki,
  widok komputera, uprawnienia, animacje, czyszczenie ciasteczek, terminal i wylogowanie.

## Uprawnienia

Aplikacja pyta o zgodę przed działaniem, które coś zmienia albo wysyła dane:

- zapis pliku w edytorze terminala (`edit <plik>`),
- każde polecenie w terminalu, jeśli włączysz tę opcję,
- wysłanie treści otwartej strony do modelu.

Trybu bypass nie ma i nie będzie. Zapis pliku, polecenia groźne
(`rm`, `mv`, `chmod`, `dd`, `curl`, przekierowania `>`) oraz wysłanie treści strony
do modelu pytają zawsze i nie da się tego wyłączyć żadnym przełącznikiem.
Wyłączyć można wyłącznie pytanie o zwykłe, nieszkodliwe polecenia.

## Zabezpieczenia

- token sesji i hasło SSH w `EncryptedSharedPreferences` z kluczem w Android Keystore,
- blokada wejścia odciskiem palca albo kodem ekranu (`BiometricPrompt`),
- `FLAG_SECURE`: brak zrzutów ekranu i brak podglądu w liście ostatnich aplikacji,
- wyłączony zwykły HTTP w całej aplikacji (`network_security_config`), także w przeglądarce,
  która przepina adresy `http://` na `https://`,
- WebView bez dostępu do plików, z `MIXED_CONTENT_NEVER_ALLOW` i Safe Browsing,
- kopie zapasowe i przenoszenie na nowy telefon wyłączone (`data_extraction_rules`),
- wersja release nie jest debugowalna.

Plan konta liczy serwer w endpoint `/profile`, na podstawie sumy doładowań
bez wliczania testowych: Free od 0 USD, Plus od 5 USD, Pro od 20 USD.

Cały zestaw ikon jest własny (`res/drawable/ic_*.xml`), a dolny pasek to pigułka,
która rozsuwa się pod wybraną zakładką i pokazuje jej nazwę.

Terminal ma dwa tryby:

- **Lokalny** — polecenia systemu Androida w piaskownicy aplikacji (`/system/bin/sh`),
  z edytorem plików (`edit <plik>`). Bez `apt` i bez roota, bo tego Android nie pozwala obejść.
- **SSH** — prawdziwa powłoka na Twoim serwerze, po JSch. Działa `apt`, `git`, `nano`,
  historia, wszystko. Wyjście leci strumieniem na żywo, a nie paczkami po komendzie.
  Dane logowania ustawiasz w Ustawieniach, hasło trafia do szyfrowanego schowka
  opartego o Keystore telefonu. Klucz hosta jest zapamiętywany przy pierwszym
  połączeniu i sprawdzany przy każdym kolejnym — jego zmiana zrywa połączenie.

## 1. Ustawienia przed budowaniem

W pliku `gradle.properties` uzupełnij dwie linijki:

```
cyphr.baseUrl=https://billing.cyphr.com.pl
cyphr.googleWebClientId=TWOJ_WEB_CLIENT_ID.apps.googleusercontent.com
```

`cyphr.googleWebClientId` to ten sam Client ID, który masz w pliku `.env` serwera
w polu `GOOGLE_CLIENT_ID`. Musi być typu „Aplikacja internetowa”, bo backend
sprawdza pole `aud` tokenu.

## 2. Budowanie bez komputera (GitHub Actions)

1. Załóż repozytorium i wrzuć do niego całą tę zawartość.
2. Wejdź w zakładkę **Actions** i uruchom przepływ **Build APK**
   (albo po prostu zrób push do gałęzi `main`).
3. Po kilku minutach pobierz artefakt `cyphr-apk`. W środku jest `app-debug.apk`.
4. Zainstaluj plik na telefonie. Trzeba zezwolić na instalację z nieznanych źródeł.

## 3. Logowanie Google

Potrzebne są **dwa** klienty OAuth w tym samym projekcie Google Cloud:

1. **Aplikacja internetowa** — jego Client ID wpisujesz do `gradle.properties`
   jako `cyphr.googleWebClientId`. To ten sam, który serwer ma w `.env`
   w polu `GOOGLE_CLIENT_ID`, bo backend sprawdza pole `aud` tokenu.
2. **Android** — nazwa pakietu `pl.cyphr.app` i odcisk SHA-1 klucza podpisującego.
   Nic z niego nie wpisujesz w aplikacji, musi tylko istnieć.

Odcisku nie musisz szukać sam. Przepływ w Actions zapisuje go do pliku
`fingerprint.txt` i dokłada do artefaktu razem z .apk. Otwierasz plik,
kopiujesz linię SHA1 i wklejasz w Google Cloud Console.

Kolejność: pierwszy build → `fingerprint.txt` → klient Android w Google Cloud →
Client ID aplikacji internetowej do `gradle.properties` → drugi build.

## 4. Drugi składnik logowania

Samo hasło albo konto Google nie wystarcza. Po zalogowaniu aplikacja prosi
o potwierdzenie tożsamości i dopiero wtedy otwiera konto. To samo wraca,
gdy aplikacja wróci z tła po dłuższej przerwie (domyślnie minuta)
oraz przy zakupie w sklepie. Wyłączyć można tylko te dwa ostatnie.

Okno potwierdzenia rysuje **system Androida**, nie aplikacja. Dlatego każdy
telefon pokazuje swoje: czytnik pod ekranem podświetla miejsce dotyku,
telefon z czytnikiem z tyłu albo w przycisku zasilania daje własną podpowiedź,
a urządzenie z rozpoznawaniem twarzy od razu skanuje twarz. Aplikacja nigdzie
nie pisze „dotknij czytnika”, bo nie ma pojęcia, gdzie on jest.

Nazwy w interfejsie też się dopasowują. Aplikacja sprawdza, co telefon ma
(`FEATURE_FINGERPRINT`, `FEATURE_FACE`, `FEATURE_IRIS`) i pisze „odcisk palca”,
„skan twarzy”, „skan tęczówki” albo „kod ekranu blokady”. Gdy blokady jeszcze
nie ma, pokazuje przycisk prowadzący prosto do systemowego ekranu jej ustawiania.

## 5. Co robi backend

Aplikacja korzysta z endpointów: `/auth/register`, `/auth/verify`, `/auth/resend`,
`/auth/login`, `/auth/google`, `/me`, `/me/usage`, `/logout`, `/v1/models`,
`/v1/chat/completions` oraz `/agents`, `/shop/packages`, `/shop/buy`
z paczki `cyphr-app.zip`. Czat i przeglądarka liczą się z salda.

## 6. Wersja do publikacji

`./gradlew assembleRelease` zbuduje wersję release podpisaną kluczem debug.
Przed wysyłką do Google Play trzeba podmienić `signingConfig` na własny keystore.
