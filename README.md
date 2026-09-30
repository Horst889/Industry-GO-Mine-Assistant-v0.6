# Industry GO Mine Assistant v0.6

Fixes in v0.6:
- alternative selection uses Accessibility first and OCR row coordinates as fallback
- if an alternative is applied late or manually, automation resumes at Bauen instead of sondieren again
- normal map Bauen button uses calibrated fallback position
- final confirmation dialog uses the lowest Bauen OCR match plus calibrated fallback
- polls for build dialog and success to tolerate slower mobile data

Safety rules remain: resource priority/minimum quality and no-Nexus verification before final build.

# Industry GO Mine Assistant – Version 0.2

Android-Hilfsapp für **Industry GO** (`de.industrygo.app`). Sie verwendet die Android-Bedienungshilfe, Bildschirmaufnahmen und lokale Texterkennung (ML Kit), um den vom Nutzer beschriebenen Minenablauf zu automatisieren.

## Eingebaute Spielregeln

Rohstoffpriorität:

1. Uran 90–100 %
2. Seltene Erden 90–100 %
3. Diamant 90–100 %
4. Titan 90–100 %
5. Gold 90–100 %
6. Silber 90–100 %
7. Öl 90–100 %
8. Kupfer 90–100 %
9. Kohle 90–100 %
10. Eisen 90–100 %
11. Stein 90–100 %
12. Lehm 10–100 %

Die Rohstoffpriorität ist wichtiger als die Qualität. Beispiel aus dem bereitgestellten Screenshot: **Lehm 99 %, Kohle 93 %, Eisen 92 % → Kohle 93 %**.

## Geometrie

Standardwerte nach den bereitgestellten Screenshots/Angaben:

- grüner Sondierungsbereich: **96 m Durchmesser**
- neu zu bauende Mine: **20 m Durchmesser**
- vorhandene Minen können **32 m** groß sein
- Raster: dichte hexagonale Kreispackung, damit möglichst viel Platz genutzt wird
- eine neue Mine wird nur geplant, wenn ihr kompletter 20-m-Kreis rechnerisch innerhalb des 96-m-Bereichs liegt
- nach jedem erfolgreichen Bau wird der Bildschirm neu ausgewertet und das Raster neu geplant

## Nexus-Sperre

Vor dem endgültigen Bauen wird der Bestätigungsdialog erneut per OCR gelesen. Die App bestätigt nur, wenn der Dialog unter anderem ausdrücklich **„kein Nexus-Verbrauch“** (oder eine englische Entsprechung) enthält. Ist die Erkennung unsicher, wird abgebrochen.

## Ablauf

1. grünen lokalen/Sondierungsbereich erkennen
2. dichtes Raster berechnen
3. belegte/dunkle Punkte vorsortieren
4. Rasterpunkt antippen
5. **Sondieren**
6. Hauptrohstoff und Alternativen lesen
7. beste zulässige Alternative nach der festen Priorität wählen
8. **Bauen** öffnen
9. Rohstoff, Qualität, Minengröße und **kein Nexus-Verbrauch** prüfen
10. im Testmodus abbrechen; im Echtmodus endgültig bauen
11. auf **„Mine gebaut“** warten
12. Karte neu scannen und nächsten Punkt bearbeiten

## Empfohlener erster Test

Die App startet standardmäßig im **Testmodus**. Testmodus zunächst eingeschaltet lassen. Dadurch wird bis zum sicheren Baudialog gegangen, aber der endgültige Bau wird nicht bestätigt.

## Installation / APK erstellen

### Variante A – komplett im Browser über GitHub Actions

1. Neues GitHub-Repository erstellen.
2. Den Inhalt dieses Projektordners hochladen.
3. Unter **Actions → Build Android APK → Run workflow** starten.
4. Nach dem Build das Artefakt **IndustryGoMineAssistant-debug** herunterladen.
5. ZIP des Artefakts öffnen und `app-debug.apk` auf dem Android-Smartphone installieren.

Die Workflow-Datei liegt bereits unter `.github/workflows/build-apk.yml`.

### Variante B – Android Studio

Projektordner in Android Studio öffnen und `Build > Build APK(s)` verwenden.

## Einrichtung auf dem Smartphone

1. App installieren und öffnen.
2. **Bedienungshilfe aktivieren** antippen.
3. `Industry GO Mine Assistant` in den Android-Bedienungshilfen einschalten.
4. Standardwerte 96 m / 20 m zunächst beibehalten.
5. Testmodus einschalten.
6. **Automatik START** drücken.
7. Industry GO öffnen und auf der Karten-/Minenansicht bleiben.

## Hinweise

- Die App benötigt Android 11 (API 30) oder neuer, weil die Accessibility-Screenshot-API verwendet wird.
- Bei Änderungen am Industry-GO-Layout können OCR/Schaltflächenpositionen angepasst werden müssen.
- Fallback-Koordinaten sind nur Reserve; vorrangig werden zugängliche UI-Texte und OCR verwendet.
- Die App umgeht keine Login-, Netzwerk-, GPS- oder Anti-Cheat-Mechanismen. Sie bedient ausschließlich die sichtbare Android-Oberfläche mit einer vom Nutzer aktivierten Bedienungshilfe.

## v0.3
- Automatik-START löst jetzt tatsächlich den Accessibility-Automationslauf aus und öffnet Industry GO.
- Android 8-10: Bildschirmaufnahme über MediaProjection als Fallback (einmalige Systemfreigabe erforderlich).
- Android 11+: weiterhin native Accessibility-Screenshot-API.
- Testmodus bleibt Standard und bricht vor dem finalen Bau-Klick ab.


## v0.4 changes
- Fixes final build confirmation: the app now targets the lowest **Bauen** label in the confirmation dialog instead of confusing it with the dialog heading.
- Retries the confirmed build once on slow devices/mobile connections.
- Reads the actual mine diameter from the build dialog (20/26/32 m supported when OCR returns it).
- Re-checks the 96 m boundary using the actual diameter before final confirmation.
- Re-plans after every successful build and keeps the detected diameter as the next planning size.
- Existing map objects are still detected visually/conservatively; exact physical size of an already-built mine cannot always be inferred from its map icon alone.

## v0.5
- Robustere Erkennung und Auswahl der drei Sondierungs-Alternativen.
- Nach Auswahl wird geprüft, ob Industry GO den Rohstoff wirklich übernommen hat und `Frei` anzeigt.
- Der große untere `Bauen`-Button wird gezielt als unterster OCR-Treffer angeklickt.
- Der Baudialog wird über mehrere kurze Prüfungen abgewartet (`Bau wird vorbereitet...`).
- Der finale `Bauen`-Button bleibt durch die Nexus-/Rohstoff-/Qualitätsprüfung abgesichert.
