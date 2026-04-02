# Recap 2026-04-02 — v0.9.1 fix testo bianco dialog nome stanza

## Stato

**Branch:** `feature/room-history-v2` → mergiato su `main`
**APK:** `builds/v0.9.1-dialog-fix/HubAgency-v0.9.1-dialog-fix-debug.apk`
**Build:** SUCCESS

---

## Problema

Nel dialog "Nome stanza" il testo digitato era invisibile: `setTextColor(Color.WHITE)`
su sfondo bianco di sistema dell'AlertDialog.

## Fix

Rimosso `setTextColor(Color.WHITE)` e `setHintTextColor(Color.GRAY)` dall'EditText
in `showNamingDialogAndSave()`. Il testo ora usa il colore di default del tema
di sistema (nero su bianco) — leggibile in tutti i contesti.

## File toccati

| File | Modifica |
|---|---|
| `ScanningActivity.kt` | rimossi 2 setTextColor/setHintTextColor dall'EditText del dialog |

**Tutto il resto invariato.**
