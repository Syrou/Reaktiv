package io.github.syrou.reaktiv.introspection

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private fun download(win: JsAny, bytes: JsAny, fileName: String, mimeType: String): String? =
    js("""
        (function(win, bytes, fileName, mimeType) {
            var doc = win.document;
            var urls = win.URL;
            if (!doc || typeof doc.createElement !== 'function' || !doc.body
                || !urls || typeof urls.createObjectURL !== 'function') {
                return 'Downloads need a browser document';
            }
            try {
                var url = urls.createObjectURL(new Blob([bytes], { type: mimeType }));
                var anchor = doc.createElement('a');
                anchor.href = url;
                anchor.download = fileName;
                anchor.rel = 'noopener';
                anchor.style.display = 'none';
                doc.body.appendChild(anchor);
                anchor.click();
                doc.body.removeChild(anchor);
                win.setTimeout(function() { urls.revokeObjectURL(url); }, 40000);
                return null;
            } catch (err) {
                return (err && err.message) ? String(err.message) : String(err);
            }
        })(win, bytes, fileName, mimeType)
    """)

public actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {

    @OptIn(ExperimentalEncodingApi::class)
    public actual fun saveToDownloads(bytes: ByteArray, fileName: String): String {
        val failure = download(
            platformContext.window,
            base64ToBytes(Base64.encode(bytes)),
            fileName,
            sessionFileMimeType(fileName)
        )
        if (failure != null) throw UnsupportedOperationException("Could not download $fileName: $failure")
        return fileName
    }
}
