package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.notice.ScholarshipNotice
import io.github.chlwhdtn03.data.notice.StartUpNotice
import io.github.chlwhdtn03.data.notice.StartUpResponse
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val STARTUP_URL = "https://startup.ssu.ac.kr/api/board/content/list"
const val SCHOLARSHIP_URL = "https://scatch.ssu.ac.kr/wp-json/wp/v2/notice"
private val noticeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal suspend fun loadStartUpNotices(pageNum: Int = 1): List<StartUpNotice> {
    if (pageNum < 1) {
        return emptyList()
    }

    return client.get(STARTUP_URL) {
        url {
            parameters.append("boardEnName", "notice")
            parameters.append("pageNum", pageNum.toString())
        }
    }.body<StartUpResponse>().data.content.list
}

internal suspend fun loadScholarships(pageNum: Int = 1): List<ScholarshipNotice> {
    if (pageNum < 1) {
        return emptyList()
    }

    return client.get(SCHOLARSHIP_URL) {
        url {
            parameters.append("notice-category", "6")
            parameters.append("page", pageNum.toString())
        }
    }.body<List<ScholarshipNotice>>()
}

/**
 * 숭실대학교 창업지원단 공지사항 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
 *
 * @param pageNum 조회할 페이지 번호 (기본값: 1)
 * @param completion 결과 수신 콜백
 */
fun loadStartUpNotices(pageNum: Int = 1, completion: (StartUpNoticesResult) -> Unit) {
    noticeScope.launch {
        val result = try {
            StartUpNoticesResult(success = true, notices = loadStartUpNotices(pageNum))
        } catch (throwable: Throwable) {
            StartUpNoticesResult(success = false, errorMessage = throwable.toResultMessage())
        }
        completion(result)
    }
}

/**
 * 숭실대학교 장학 공지사항 목록을 비동기 방식으로 조회하고 그 결과를 completion 콜백으로 전달합니다.
 *
 * @param pageNum 조회할 페이지 번호 (기본값: 1)
 * @param completion 결과 수신 콜백
 */
fun loadScholarships(pageNum: Int = 1, completion: (ScholarshipNoticesResult) -> Unit) {
    noticeScope.launch {
        val result = try {
            ScholarshipNoticesResult(success = true, notices = loadScholarships(pageNum))
        } catch (throwable: Throwable) {
            ScholarshipNoticesResult(success = false, errorMessage = throwable.toResultMessage())
        }
        completion(result)
    }
}

private fun Throwable.toResultMessage(): String {
    return message ?: "알 수 없는 오류가 발생했습니다."
}
