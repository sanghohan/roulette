package net.donjiral.pingpong.ytsummary

import com.fasterxml.jackson.databind.ObjectMapper
import net.donjiral.pingpong.aicheck.EmailAlertService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 유튜브 영상 요약 서비스.
 * 1) 영상 자막(수동/자동생성)을 가져와서
 * 2) Claude API(Messages)로 한국어 요약을 생성합니다.
 * - 자막 없는 영상은 지원하지 않음 (STT 미지원)
 * - videoId 기준 캐시로 중복 호출 비용 방지
 */
@Service
class YtSummaryService(
    private val mapper: ObjectMapper,
    private val alert: EmailAlertService,
    @Value("\${app.ytsummary.api-url:https://api.anthropic.com}") private val apiUrl: String,
    @Value("\${app.ytsummary.api-key:}") private val apiKey: String,
    @Value("\${app.ytsummary.model:claude-haiku-4-5-20251001}") private val model: String,
    @Value("\${app.ytsummary.max-transcript-chars:30000}") private val maxChars: Int
) {
    private val log = LoggerFactory.getLogger(YtSummaryService::class.java)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, YtSummaryResponse>>()
    private val cacheTtlMs = 7 * 24 * 60 * 60 * 1000L // 요약은 잘 안 변하므로 7일

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun summarize(rawUrl: String): YtSummaryResponse {
        val videoId = extractYoutubeId(rawUrl)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 유튜브 URL이 아니에요")

        cache[videoId]?.let { (ts, resp) ->
            if (System.currentTimeMillis() - ts < cacheTtlMs) return resp.copy(cached = true)
        }
        if (cache.size > 5000) cache.clear()

        if (apiKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "요약 기능이 아직 설정되지 않았어요. (API 키 미설정)")
        }

        val (title, author, thumbnail) = fetchMeta(videoId)
        val (lang, transcript) = fetchTranscript(videoId)
            ?: throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "이 영상은 자막이 없어서 요약할 수 없어요. 자막(자동생성 포함)이 있는 영상만 지원합니다."
            )

        val trimmed = if (transcript.length > maxChars) transcript.take(maxChars) else transcript
        val summary = askClaude(title, author, lang, trimmed)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "요약 생성에 실패했어요. 잠시 후 다시 시도해주세요.")

        log.info("[ytsummary] 요약 완료 videoId={} lang={} 자막 {}자", videoId, lang, trimmed.length)
        val result = YtSummaryResponse(
            videoId = videoId, title = title, author = author, thumbnail = thumbnail,
            language = lang, summary = summary, cached = false, transcriptChars = trimmed.length
        )
        cache[videoId] = System.currentTimeMillis() to result
        return result
    }

    // ---- 유튜브 공개 메타데이터 (oEmbed) ----
    private fun fetchMeta(videoId: String): Triple<String?, String?, String?> {
        var title: String? = null; var author: String? = null
        var thumb: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        try {
            val oembed = "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val res = http.send(
                HttpRequest.newBuilder(URI.create(oembed)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (res.statusCode() == 200) {
                val n = mapper.readTree(res.body())
                title = n.get("title")?.asText(); author = n.get("author_name")?.asText()
                n.get("thumbnail_url")?.asText()?.let { thumb = it }
            }
        } catch (_: Exception) {}
        return Triple(title, author, thumb)
    }

    // ---- 자막 추출 ----
    /** watch 페이지에서 captionTracks를 찾아 자막 텍스트를 가져온다. 실패 시 null */
    private fun fetchTranscript(videoId: String): Pair<String, String>? {
        val html = try {
            val req = HttpRequest.newBuilder(URI.create("https://www.youtube.com/watch?v=$videoId&hl=ko"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent)
                .header("Accept-Language", "ko,en;q=0.8")
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) {
                log.warn("[ytsummary] watch 페이지 HTTP {} videoId={}", res.statusCode(), videoId); return null
            }
            res.body()
        } catch (e: Exception) {
            log.warn("[ytsummary] watch 페이지 요청 실패 videoId={} : {}", videoId, e.message); return null
        }

        val tracksJson = extractJsonArray(html, "\"captionTracks\":") ?: run {
            log.info("[ytsummary] captionTracks 없음 videoId={}", videoId); return null
        }
        val tracks = try { mapper.readTree(tracksJson) } catch (e: Exception) {
            log.warn("[ytsummary] captionTracks 파싱 실패: {}", e.message); return null
        }
        if (!tracks.isArray || tracks.isEmpty) return null

        // 언어 선택: ko 수동 > ko 자동 > en 수동 > en 자동 > 첫 트랙
        fun score(n: com.fasterxml.jackson.databind.JsonNode): Int {
            val lang = n.get("languageCode")?.asText() ?: ""
            val asr = n.get("kind")?.asText() == "asr"
            return when {
                lang.startsWith("ko") && !asr -> 0
                lang.startsWith("ko") -> 1
                lang.startsWith("en") && !asr -> 2
                lang.startsWith("en") -> 3
                else -> 4
            }
        }
        val best = tracks.minByOrNull { score(it) } ?: return null
        val baseUrl = best.get("baseUrl")?.asText() ?: return null
        val lang = best.get("languageCode")?.asText() ?: "?"

        // json3 포맷으로 받아 세그먼트 텍스트 결합
        val text = try {
            val sep = if (baseUrl.contains("?")) "&" else "?"
            val req = HttpRequest.newBuilder(URI.create(baseUrl + sep + "fmt=json3"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent)
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299 || res.body().isBlank()) return null
            val events = mapper.readTree(res.body()).get("events") ?: return null
            buildString {
                for (ev in events) {
                    val segs = ev.get("segs") ?: continue
                    for (s in segs) append(s.get("utf8")?.asText() ?: "")
                    append(" ")
                }
            }.replace(Regex("\\s+"), " ").trim()
        } catch (e: Exception) {
            log.warn("[ytsummary] 자막 다운로드 실패 videoId={} : {}", videoId, e.message); return null
        }
        if (text.length < 50) return null // 사실상 빈 자막
        return lang to text
    }

    /** html에서 marker 뒤의 JSON 배열을 대괄호 짝 맞춰 추출 */
    private fun extractJsonArray(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('[', idx + marker.length)
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (esc) { esc = false; continue }
            when {
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '[' -> depth++
                !inStr && c == ']' -> { depth--; if (depth == 0) return html.substring(start, i + 1) }
            }
        }
        return null
    }

    // ---- Claude API 요약 ----
    private fun askClaude(title: String?, author: String?, lang: String, transcript: String): String? {
        val system = "당신은 유튜브 영상 요약 전문가입니다. 시청자가 영상을 보지 않고도 핵심을 파악할 수 있게 한국어로 요약하세요. " +
            "형식: 첫 줄에 '핵심 한 줄' 요약, 그 아래 주요 내용을 5~8개의 불릿(•)으로, 마지막에 '결론' 한두 문장. " +
            "자막 원문에 없는 내용을 지어내지 마세요. 광고/인사말 등 부수 내용은 생략하세요."
        val user = buildString {
            append("다음 유튜브 영상의 자막을 요약해주세요.\n")
            title?.let { append("제목: $it\n") }
            author?.let { append("채널: $it\n") }
            append("자막 언어: $lang\n\n--- 자막 시작 ---\n")
            append(transcript)
            append("\n--- 자막 끝 ---")
        }
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "max_tokens" to 1024,
                "system" to system,
                "messages" to listOf(mapOf("role" to "user", "content" to user))
            )
        )
        val req = HttpRequest.newBuilder(URI.create("$apiUrl/v1/messages"))
            .timeout(Duration.ofSeconds(60))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        var res = http.send(req, HttpResponse.BodyHandlers.ofString())
        var attempt = 0
        while (res.statusCode() == 429 && attempt < 2) {
            val waitSec = res.headers().firstValue("retry-after").orElse("").toLongOrNull() ?: (3L + attempt * 3)
            log.warn("[ytsummary] 429 throttle, {}초 후 재시도 ({}회차)", waitSec, attempt + 1)
            Thread.sleep(waitSec * 1000)
            res = http.send(req, HttpResponse.BodyHandlers.ofString())
            attempt++
        }
        if (res.statusCode() !in 200..299) {
            log.warn("[ytsummary] Claude HTTP {} 응답: {}", res.statusCode(), res.body().take(500))
            val lower = res.body().lowercase()
            if (res.statusCode() == 402 || lower.contains("credit") || lower.contains("billing")) {
                alert.creditExhausted("[ytsummary] HTTP ${res.statusCode()}: ${res.body().take(200)}")
            }
            return null
        }
        val node = mapper.readTree(res.body())
        val text = node.get("content")?.firstOrNull()?.get("text")?.asText()
        return text?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("""youtu\.be/([\w-]{11})"""),
            Regex("""[?&]v=([\w-]{11})"""),
            Regex("""youtube\.com/embed/([\w-]{11})"""),
            Regex("""youtube\.com/shorts/([\w-]{11})""")
        )
        for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
        if (Regex("""^[\w-]{11}$""").matches(url.trim())) return url.trim()
        return null
    }
}
