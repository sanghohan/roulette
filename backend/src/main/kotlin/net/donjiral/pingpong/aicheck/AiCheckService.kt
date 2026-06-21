package net.donjiral.pingpong.aicheck

import com.fasterxml.jackson.databind.ObjectMapper
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
import kotlin.math.abs

@Service
class AiCheckService(
    private val mapper: ObjectMapper,
    @Value("\${app.aicheck.provider:}") private val provider: String,
    @Value("\${app.aicheck.api-url:https://api.replicate.com/v1}") private val apiUrl: String,
    @Value("\${app.aicheck.api-key:}") private val apiKey: String,
    // 비전 모델 (owner/name 형식). 다른 모델로 바꾸려면 환경변수 AICHECK_IMAGE_MODEL 설정
    @Value("\${app.aicheck.image-model:yorickvp/llava-13b}") private val imageModel: String
) {
    private val log = LoggerFactory.getLogger(AiCheckService::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AiCheckResponse>>()
    private val cacheTtlMs = 6 * 60 * 60 * 1000L

    private val prompt =
        "You are an expert AI-generated image detector. This image is a frame from a video. " +
        "Respond with ONLY a single integer from 0 to 100 indicating the probability (percent) " +
        "that this frame was generated or heavily manipulated by AI. No words, just the number."

    fun analyze(rawUrl: String): AiCheckResponse {
        val videoId = extractYoutubeId(rawUrl)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 유튜브 URL이 아니에요")

        cache[videoId]?.let { (ts, resp) -> if (System.currentTimeMillis() - ts < cacheTtlMs) return resp }
        if (cache.size > 5000) cache.clear()

        val (title, author, thumbnail) = fetchMeta(videoId)

        val configured = provider.isNotBlank() && apiKey.isNotBlank()
        val result = if (configured) realCheck(videoId, title, author, thumbnail)
                     else demoCheck(videoId, title, author, thumbnail)
        cache[videoId] = System.currentTimeMillis() to result
        return result
    }

    // ---- 유튜브 공개 메타데이터 ----
    private fun fetchMeta(videoId: String): Triple<String?, String?, String?> {
        var title: String? = null; var author: String? = null
        var thumb: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        try {
            val oembed = "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val res = http.send(HttpRequest.newBuilder(URI.create(oembed)).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val n = mapper.readTree(res.body())
                title = n.get("title")?.asText(); author = n.get("author_name")?.asText()
                n.get("thumbnail_url")?.asText()?.let { thumb = it }
            }
        } catch (_: Exception) {}
        return Triple(title, author, thumb)
    }

    // ---- 실제 판별 (영상 프레임 → Replicate 비전 모델) ----
    private fun realCheck(videoId: String, title: String?, author: String?, thumbnail: String?): AiCheckResponse {
        // 대표 프레임 2장 (앞부분/뒷부분)으로 평균
        val frames = listOf(1, 3).map { "https://i.ytimg.com/vi/$videoId/$it.jpg" }
        val scores = mutableListOf<Int>()
        for (f in frames) {
            try {
                askVisionModel(f)?.let { scores.add(it) }
            } catch (e: Exception) {
                log.warn("[aicheck] 프레임 분석 실패 url={} : {}", f, e.message)
            }
        }
        if (scores.isEmpty()) {
            log.error("[aicheck] 모든 프레임 분석 실패 videoId={} model={}", videoId, imageModel)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "영상 분석에 실패했어요. 잠시 후 다시 시도해주세요.")
        }
        val video = scores.average().toInt().coerceIn(0, 100)
        log.info("[aicheck] 분석 완료 videoId={} 확률={}% 판정='{}' (프레임 {}장)", videoId, video, verdictText(video), scores.size)
        return AiCheckResponse(
            videoId = videoId, title = title, author = author, thumbnail = thumbnail,
            videoAiProbability = video, audioSupported = false,
            verdict = verdictText(video), demo = false,
            note = "영상 프레임 ${scores.size}장을 AI 모델이 분석한 추정치예요. 참고용이며 100% 정확하지 않습니다. (오디오/음원 판별은 현재 지원하지 않아요)"
        )
    }

    @Volatile private var cachedVersion: String? = null

    /** 모델의 최신 버전 ID를 조회(커뮤니티 모델 호출에 필요). 캐시함. */
    private fun resolveVersion(): String? {
        cachedVersion?.let { return it }
        val parts = imageModel.split("/")
        if (parts.size != 2) { log.error("[aicheck] AICHECK_IMAGE_MODEL 형식 오류: {}", imageModel); return null }
        val req = HttpRequest.newBuilder(URI.create("$apiUrl/models/${parts[0]}/${parts[1]}"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $apiKey")
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) {
            log.warn("[aicheck] 모델 조회 실패 HTTP {} model={} body={}", res.statusCode(), imageModel, res.body().take(300))
            return null
        }
        val v = mapper.readTree(res.body()).get("latest_version")?.get("id")?.asText()
        if (v.isNullOrBlank()) { log.warn("[aicheck] latest_version 없음 model={}", imageModel); return null }
        cachedVersion = v
        return v
    }

    /** Replicate 비전 모델 호출. Prefer:wait 로 동기 응답 받음. 0~100 정수 반환 */
    private fun askVisionModel(imageUrl: String): Int? {
        val version = resolveVersion() ?: return null
        val endpoint = "$apiUrl/predictions"
        val body = mapper.writeValueAsString(
            mapOf("version" to version, "input" to mapOf("image" to imageUrl, "prompt" to prompt))
        )
        val req = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Prefer", "wait")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        // 429(throttle) 시 잠깐 쉬었다 재시도
        var res = http.send(req, HttpResponse.BodyHandlers.ofString())
        var attempt = 0
        while (res.statusCode() == 429 && attempt < 3) {
            val waitSec = (res.headers().firstValue("retry-after").orElse("")).toLongOrNull() ?: (2L + attempt * 2)
            log.warn("[aicheck] 429 throttle, {}초 후 재시도 ({}회차)", waitSec, attempt + 1)
            Thread.sleep(waitSec * 1000)
            res = http.send(req, HttpResponse.BodyHandlers.ofString())
            attempt++
        }
        if (res.statusCode() !in 200..299) {
            log.warn("[aicheck] Replicate HTTP {} 응답: {}", res.statusCode(), res.body().take(500))
            return null
        }
        val node = mapper.readTree(res.body())
        val status = node.get("status")?.asText()
        if (status != "succeeded") {
            log.warn("[aicheck] Replicate status={} body={}", status, res.body().take(500))
            return null
        }
        // output: 문자열 또는 문자열 배열
        val out = node.get("output")
        val text = when {
            out == null -> ""
            out.isArray -> out.joinToString("") { it.asText() }
            else -> out.asText()
        }
        val m = Regex("""\b(100|\d{1,2})\b""").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }

    private fun verdictText(p: Int) = when {
        p >= 70 -> "AI 생성 가능성 높음 (추정)"
        p >= 40 -> "판단 애매 / 혼합 가능성 (추정)"
        else -> "실제 촬영 가능성 높음 (추정)"
    }

    // ---- 데모 (키 미설정 시) ----
    private fun demoCheck(videoId: String, title: String?, author: String?, thumbnail: String?): AiCheckResponse {
        val h = abs(videoId.hashCode())
        val video = 10 + (h % 80)
        return AiCheckResponse(
            videoId = videoId, title = title, author = author, thumbnail = thumbnail,
            videoAiProbability = video, audioSupported = false,
            verdict = verdictText(video), demo = true,
            note = "⚠️ 데모 추정 결과입니다. 실제 AI 탐지가 아니며, 탐지 API를 연결하면 실제 분석으로 바뀝니다."
        )
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
