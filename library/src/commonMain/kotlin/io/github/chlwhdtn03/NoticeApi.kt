package io.github.chlwhdtn03

import io.github.chlwhdtn03.data.notice.ScholarshipNotice
import io.github.chlwhdtn03.data.notice.StartUpNotice
import io.github.chlwhdtn03.data.notice.StartUpResponse
import io.ktor.client.call.*
import io.ktor.client.request.*

const val STARTUP_URL = "https://startup.ssu.ac.kr/api/board/content/list"
const val SCHOLARSHIP_URL = "https://scatch.ssu.ac.kr/wp-json/wp/v2/notice"

suspend fun loadStartUpNotices(pageNum: Int = 1): List<StartUpNotice> {
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

suspend fun loadScholarships(pageNum: Int = 1): List<ScholarshipNotice> {
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
