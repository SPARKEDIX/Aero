package com.aero.voice.search

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Minimal RSS 2.0 reader used by the news sources. */
internal object Rss {

    fun parse(xml: String, source: String, limit: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            var inItem = false
            var title: String? = null
            var link: String? = null
            var desc: String? = null
            var date: String? = null

            while (event != XmlPullParser.END_DOCUMENT && out.size < limit) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                        "item" -> {
                            inItem = true
                            title = null
                            link = null
                            desc = null
                            date = null
                        }
                        "title" -> if (inItem) title = parser.nextText()
                        "link" -> if (inItem) link = parser.nextText()
                        "description" -> if (inItem) desc = parser.nextText()
                        "pubdate" -> if (inItem) date = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (inItem && parser.name.equals("item", true)) {
                        inItem = false
                        val t = clean(title)
                        val l = link?.trim().orEmpty()
                        if (t.isNotBlank() && l.isNotBlank()) {
                            out.add(WebResult(t, clean(desc).take(300), l, source, date))
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Malformed feed — return whatever parsed so far.
        }
        return out
    }

    private fun clean(s: String?): String =
        Html.fromHtml(s ?: "", Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ")
            .trim()
}
