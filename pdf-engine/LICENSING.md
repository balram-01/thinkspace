# Licensing & Legal Compliance

## 1. Commercial Usability Statement

The `pdf-engine` module is designed for **unrestricted commercial redistribution and proprietary integration**.

**Strict Policy**:
No **GPL**, **LGPL**, **AGPL** (such as iText or MuPDF), or other copyleft dependencies are used. You may integrate, distribute, and embed this library into closed-source commercial applications without being required to open-source your proprietary workspace or application code.

---

## 2. Dependency Audit Matrix

| Dependency Artifact | Version | Primary License | Permitted for Commercial Use? | Redistribution Notice Requirements |
|---|---|---|---|---|
| `com.tom-roush:pdfbox-android` | `2.0.27.0` | **Apache License 2.0** | **YES** | Include Apache 2.0 license notice & copyright header in distribution. |
| `org.apache.pdfbox:fontbox` | `2.0.27` | **Apache License 2.0** | **YES** | Include Apache 2.0 notice. |
| `com.google.mlkit:text-recognition` | `16.0.1` | **Apache 2.0 / Google APIs Terms** | **YES** | Free on-device inference, no per-call API billing. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `1.8.1` | **Apache License 2.0** | **YES** | Standard Apache 2.0 terms. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`| `1.8.1` | **Apache License 2.0** | **YES** | Standard Apache 2.0 terms. |
| `androidx.core:core-ktx` | `1.13.1` | **Apache License 2.0** | **YES** | Standard Apache 2.0 terms. |
| `androidx.annotation:annotation` | `1.8.2` | **Apache License 2.0** | **YES** | Standard Apache 2.0 terms. |

---

## 3. Why Not Alternative PDF Libraries?

### iText 7 / iTextCore
- **License**: **AGPLv3 (Affero General Public License)**
- **Implications**: The AGPL requires any software linking against it (or even accessed over a network) to release its entire source code under the AGPL. A commercial license from Apryse/iText costs several thousand dollars per application/server annually.
- **Verdict**: **Rejected** due to severe viral licensing risks.

### Artifex MuPDF
- **License**: **AGPLv3** or Proprietary Commercial
- **Implications**: Similar to iText, standard open-source MuPDF carries viral AGPL obligations unless a commercial agreement is purchased from Artifex.
- **Verdict**: **Rejected** for default open commercial integration.

### Android Native `PdfRenderer` (`android.graphics.pdf.PdfRenderer`)
- **License**: Apache 2.0 / AOSP
- **Implications**: Permissive, but technically incapable of extracting text, words, characters, font metadata, annotations, or embedded images.
- **Verdict**: Insufficient feature set on its own.

---

## 4. Compliance Checklist for Application Distributors

When shipping an Android application containing `pdf-engine`:
1. Include a copy of the Apache License 2.0 in your app's "Open Source Licenses" screen or `NOTICE` file.
2. Maintain standard copyright notices for the Apache Software Foundation and Tom Roush.
3. No code changes to your proprietary application need to be published.
