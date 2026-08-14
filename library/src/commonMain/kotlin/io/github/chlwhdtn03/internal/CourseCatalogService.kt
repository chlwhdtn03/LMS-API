package io.github.chlwhdtn03.internal

import io.github.chlwhdtn03.data.Lms.*
import io.github.chlwhdtn03.decodeHtmlEntities
import io.github.chlwhdtn03.stripHtmlTags

/** 수강편람(ZCMW2100)의 검색 조건과 전체 결과 조회를 담당합니다. */
internal class CourseCatalogService(
    private val webDynpro: WebDynproService,
) {
    suspend fun getSearchOptions(query: CourseCatalogQuery): CourseCatalogSearchOptions {
        val configured = configure(query)
        return CourseCatalogSearchOptions(
            year = query.year,
            semester = query.semester,
            category = query.category,
            filters = configured.filters.mapIndexed { index, control ->
                control.toPublicFilter(index, filterName(query.category, index))
            },
            acceptsKeyword = query.category in KEYWORD_CATEGORIES,
        )
    }

    suspend fun search(query: CourseCatalogQuery): CourseCatalogTable {
        var context = submitSearch(query)

        val merged = linkedMapOf<String, CourseCatalogCourse>()
        var totalCount = parseTotalCount(context.html)
        var page = 0
        while (page < MAX_PAGE_COUNT) {
            val pageResult = collectCurrentPage(context, merged)
            context = pageResult.context
            totalCount = maxOf(totalCount, pageResult.reportedCount)

            if (totalCount > 0 && merged.size >= totalCount) break
            val nextButtonId = pageResult.nextButtonId ?: break
            context = submit(context, buttonEvent(nextButtonId), nextButtonId)
            page++
        }

        return CourseCatalogTable(
            year = query.year,
            semester = query.semester,
            category = query.category,
            totalCourseCount = maxOf(totalCount, merged.size),
            items = merged.values.map { course ->
                course.copy(year = query.year, semester = query.semester)
            },
        )
    }

    /** 선택한 강좌 한 건의 계획 버튼만 눌러, 아직 접근하지 않은 일회성 URL을 반환합니다. */
    suspend fun getPlanUrl(
        query: CourseCatalogQuery,
        subjectCode: String,
        section: String,
    ): String {
        require(subjectCode.isNotBlank()) { "과목번호는 비어 있을 수 없습니다." }

        var context = submitSearch(query)
        var page = 0
        while (page < MAX_PAGE_COUNT) {
            val pageHtml = context.html
            var rows = parseCourseRows(pageHtml)
            resolvePlanUrl(context, rows, subjectCode, section)?.let { return it }

            val tableId = findResultTableId(pageHtml)
            if (tableId != null) {
                val rowCount = parseTableRowCount(pageHtml, tableId)
                var firstVisibleRow = rows.size
                while (firstVisibleRow in 1 until minOf(rowCount, PAGE_SIZE)) {
                    context = submit(
                        context,
                        tableScrollEvent(tableId, firstVisibleRow),
                        tableId,
                    )
                    rows = parseCourseRows(context.html)
                    resolvePlanUrl(context, rows, subjectCode, section)?.let { return it }
                    if (rows.isEmpty()) break
                    firstVisibleRow += rows.size
                }
            }

            val nextButtonId = findNextPageButtonId(pageHtml) ?: break
            context = submit(context, buttonEvent(nextButtonId), nextButtonId)
            page++
        }
        return ""
    }

    fun parseTable(
        html: String,
        query: CourseCatalogQuery,
    ): CourseCatalogTable {
        val items = parseCourses(html)
        return CourseCatalogTable(
            year = query.year,
            semester = query.semester,
            category = query.category,
            totalCourseCount = maxOf(parseTotalCount(html), items.size),
            items = items.map { course ->
                course.copy(year = query.year, semester = query.semester)
            },
        )
    }

    private suspend fun configure(query: CourseCatalogQuery): ConfiguredContext {
        require(query.year.matches(Regex("""\d{4}"""))) { "학년도는 네 자리 숫자여야 합니다." }

        var context = webDynpro.openSession(URL, APP_NAME)
        val initialHtml = context.html
        val comboControls = parseComboControls(initialHtml)
        val yearControl = comboControls.firstOrNull { it.label.contains("학년도") }
            ?: throw IllegalStateException("수강편람 학년도 선택창을 찾지 못했습니다.")
        val semesterControl = comboControls.firstOrNull { it.label == "학기" }
            ?: throw IllegalStateException("수강편람 학기 선택창을 찾지 못했습니다.")
        val pageSizeControl = comboControls.firstOrNull { it.label.contains("줄수") }
            ?: throw IllegalStateException("수강편람 페이지 줄수 선택창을 찾지 못했습니다.")
        val searchButtonId = findButtonId(initialHtml, "검색")
            ?: throw IllegalStateException("수강편람 검색 버튼을 찾지 못했습니다.")

        if (yearControl.selectedKey != query.year) {
            context = submit(context, comboEvent(yearControl.id, query.year), yearControl.id)
        }
        if (semesterControl.selectedKey != query.semester.code) {
            context = submit(
                context,
                comboEvent(semesterControl.id, query.semester.code),
                semesterControl.id,
            )
        }

        val tabStripId = findTabStripId(initialHtml)
            ?: throw IllegalStateException("수강편람 조회 유형 탭을 찾지 못했습니다.")
        val tab = findTab(initialHtml, query.category)
            ?: throw IllegalStateException("${query.category.displayName} 조회 탭을 찾지 못했습니다.")
        if (!tab.selected) {
            context = submit(context, tabEvent(tabStripId, tab.id), tab.id)
        }

        val globalControlIds = setOf(
            yearControl.id,
            semesterControl.id,
            pageSizeControl.id,
        )
        var filters = categoryComboControls(context.html, globalControlIds)
        if (filters.isEmpty() && tab.selected) {
            filters = categoryComboControls(initialHtml, globalControlIds)
        }
        query.filterKeys.forEachIndexed { index, key ->
            if (key.isBlank()) return@forEachIndexed
            val filter = filters.getOrNull(index)
                ?: throw IllegalArgumentException(
                    "${query.category.displayName} 조회의 ${index + 1}번째 필터를 찾지 못했습니다.",
                )
            require(filter.options.isEmpty() || filter.options.any { it.key == key }) {
                "${filterName(query.category, index)}에 존재하지 않는 옵션 키입니다: $key"
            }
            context = submit(context, comboEvent(filter.id, key), filter.id)
            filters = mergeControls(
                filters,
                categoryComboControls(context.html, globalControlIds),
            )
        }

        if (pageSizeControl.selectedKey != PAGE_SIZE_KEY) {
            context = submit(
                context,
                comboEvent(pageSizeControl.id, PAGE_SIZE_KEY),
                pageSizeControl.id,
            )
        }

        val activeSearchButtonId = findButtonId(context.html, "검색") ?: searchButtonId
        val keywordControlId = findKeywordControlId(context.html)
            ?: findKeywordControlId(initialHtml)
        return ConfiguredContext(
            context = context,
            filters = filters,
            keywordControlId = keywordControlId,
            searchButtonId = activeSearchButtonId,
        )
    }

    private suspend fun submitSearch(query: CourseCatalogQuery): WebDynproContext {
        val configured = configure(query)
        var context = configured.context
        if (query.keyword.isNotBlank()) {
            val keywordControlId = configured.keywordControlId
                ?: throw IllegalArgumentException(
                    "${query.category.displayName} 조회에는 검색어를 사용할 수 없습니다.",
                )
            context = submit(
                context,
                inputChangeEvent(keywordControlId, query.keyword),
                keywordControlId,
            )
        }
        return submit(
            context,
            buttonEvent(configured.searchButtonId),
            configured.searchButtonId,
        )
    }

    private suspend fun collectCurrentPage(
        initialContext: WebDynproContext,
        merged: LinkedHashMap<String, CourseCatalogCourse>,
    ): PageResult {
        var context = initialContext
        val pageHtml = context.html
        val initialRows = parseCourseRows(pageHtml).map { it.course }
        initialRows.forEach { merged.addIfAbsent(it) }
        val nextButtonId = findNextPageButtonId(pageHtml)
        val totalCount = parseTotalCount(pageHtml)
        val tableId = findResultTableId(pageHtml) ?: return PageResult(
            context,
            nextButtonId,
            totalCount,
        )
        val rowCount = parseTableRowCount(pageHtml, tableId)
        var firstVisibleRow = initialRows.size
        var unchangedCount = 0
        while (firstVisibleRow in 1 until minOf(rowCount, PAGE_SIZE)) {
            val beforeCount = merged.size
            context = submit(
                context,
                tableScrollEvent(tableId, firstVisibleRow),
                tableId,
            )
            val rows = parseCourseRows(context.html).map { it.course }
            rows.forEach { merged.addIfAbsent(it) }
            unchangedCount = if (merged.size == beforeCount) unchangedCount + 1 else 0
            if (rows.isEmpty() || unchangedCount >= 2) break
            firstVisibleRow += rows.size
        }
        return PageResult(context, nextButtonId, maxOf(rowCount, totalCount))
    }

    private fun parseCourses(html: String): List<CourseCatalogCourse> {
        return parseCourseRows(html).map { it.course }
    }

    private fun parseCourseRows(html: String): List<ParsedCourse> {
        val tableRange = findResultTableRange(html)
        val rangeStart = tableRange?.first ?: 0
        val rangeEndExclusive = tableRange?.let { it.last + 1 } ?: html.length
        val headerSchema = parseCourseColumnSchema(html, rangeStart, rangeEndExclusive)
        val result = mutableListOf<ParsedCourse>()
        for (row in COURSE_ROW_REGEX.findAll(html, rangeStart)) {
            if (row.range.first >= rangeEndExclusive) break
            val cells = COURSE_CELL_REGEX.findAll(row.groupValues[1])
                .associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val schema = headerSchema ?: CourseColumnSchema.fromCells(cells)
            if (schema == null) continue
            val subjectCode = cells.textAt(schema.subjectCode).trim()
            val subjectName = cells.textAt(schema.subjectName).trim()
            if (subjectCode.isBlank() || subjectName.isBlank()) continue

            val planHtml = cells[schema.plan].orEmpty()
            result += ParsedCourse(
                course = CourseCatalogCourse(
                    plan = extractNavigationUrl(planHtml).orEmpty(),
                    primaryClassification = cells.textAt(schema.primaryClassification),
                    multiMajorClassification = cells.textAt(schema.multiMajorClassification),
                    engineeringCertification = cells.textAt(schema.engineeringCertification),
                    curriculumArea = cells.textAt(schema.curriculumArea),
                    subjectCode = subjectCode,
                    subjectName = subjectName,
                    registrationNotice = cells.textAt(schema.registrationNotice),
                    courseType = cells.textAt(schema.courseType),
                    section = cells.textAt(schema.section),
                    professor = cells.textAt(schema.professor),
                    department = cells.textAt(schema.department),
                    hoursCredits = cells.textAt(schema.hoursCredits),
                    enrollmentCapacity = cells.textAt(schema.enrollmentCapacity),
                    remainingSeats = cells.textAt(schema.remainingSeats),
                    schedule = cells.textAt(schema.schedule),
                    program = cells.textAt(schema.program),
                    targetStudents = cells.textAt(schema.targetStudents),
                ),
                planButtonId = findPlanButtonId(planHtml),
            )
        }
        return result
    }

    private fun parseCourseColumnSchema(
        html: String,
        rangeStart: Int,
        rangeEndExclusive: Int,
    ): CourseColumnSchema? {
        val headers = COURSE_HEADER_REGEX.findAll(html, rangeStart)
            .takeWhile { it.range.first < rangeEndExclusive }
            .map { it.groupValues[1].stripHtmlTags().replace(WHITESPACE_REGEX, "") }
            .toList()
        if (headers.isEmpty()) return null

        fun indexOf(vararg names: String): Int? {
            return headers.indexOfFirst { it in names }.takeIf { it >= 0 }
        }

        val subjectCode = indexOf("과목번호") ?: return null
        val subjectName = indexOf("과목명") ?: return null
        return CourseColumnSchema(
            plan = indexOf("계획", "강의계획서유무") ?: 0,
            primaryClassification = indexOf("이수구분(주전공)", "이수구분"),
            multiMajorClassification = indexOf("이수구분(다전공)", "다전공구분"),
            engineeringCertification = indexOf("공학인증"),
            curriculumArea = indexOf("교과영역"),
            subjectCode = subjectCode,
            subjectName = subjectName,
            registrationNotice = indexOf("수강유의사항"),
            courseType = indexOf("강좌유형정보", "강좌유형"),
            section = indexOf("분반"),
            professor = indexOf("교수명"),
            department = indexOf("개설학과"),
            hoursCredits = indexOf("시간/학점(설계)", "시간/학점"),
            enrollmentCapacity = indexOf("수강인원"),
            remainingSeats = indexOf("여석"),
            schedule = indexOf("강의시간(강의실)", "강의시간"),
            program = indexOf("과정"),
            targetStudents = indexOf("수강대상"),
        )
    }

    private fun Map<Int, String>.textAt(index: Int?): String {
        return index?.let { this[it] }?.stripHtmlTags().orEmpty()
    }

    /** `null`은 대상 없음, 빈 문자열은 대상은 있으나 계획서 없음입니다. */
    private suspend fun resolvePlanUrl(
        context: WebDynproContext,
        rows: List<ParsedCourse>,
        subjectCode: String,
        section: String,
    ): String? {
        val row = rows.firstOrNull { candidate ->
            candidate.course.subjectCode == subjectCode &&
                (section.isBlank() || candidate.course.section == section)
        } ?: return null
        if (row.course.plan.isNotBlank()) return row.course.plan
        val buttonId = row.planButtonId ?: return ""
        return webDynpro.resolveExternalWindowUrls(context, listOf(buttonId))
            .urlsByButtonId[buttonId]
            .orEmpty()
    }

    private fun findPlanButtonId(html: String): String? {
        return Regex(
            """<(?:div|button)\b(?=[^>]*\bid="([^"]+)")(?=[^>]*\bct="B")[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
    }

    private fun parseComboControls(html: String): List<ComboControl> {
        val inputRegex = Regex(
            """<input\b(?=[^>]*\bct="CB")(?=[^>]*\bid="([^"]+)")(?=[^>]*\blsdata="([^"]*)")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val listRegex = Regex(
            """<div\b(?=[^>]*\bct="LIB_P")(?=[^>]*\bid="([^"]+)")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val lists = listRegex.findAll(html).toList()
        return inputRegex.findAll(html).map { input ->
            val id = input.groupValues[1]
            val data = input.groupValues[2]
            val listId = dataValue(data, 3)
            val listStart = lists.firstOrNull { it.groupValues[1] == listId }
            val nextListStart = listStart?.let { start ->
                lists.firstOrNull { it.range.first > start.range.first }?.range?.first
            } ?: html.length
            val optionStart = listStart?.range?.first ?: nextListStart
            val options = Regex(
                """<div\b[^>]*\bdata-itemkey="([^"]*)"[^>]*\bdata-itemvalue1="([^"]*)"[^>]*>""",
                RegexOption.IGNORE_CASE,
            ).findAll(html, optionStart)
                .takeWhile { it.range.first < nextListStart }
                .map { option ->
                    CourseCatalogFilterOption(
                        key = option.groupValues[1].decodeHtmlEntities(),
                        label = option.groupValues[2].decodeHtmlEntities(),
                    )
                }.distinctBy { it.key to it.label }.toList()
            ComboControl(
                id = id,
                sourceIndex = input.range.first,
                label = findLabel(html, id),
                selectedKey = dataValue(data, 4),
                selectedLabel = dataValue(data, 5),
                options = options,
            )
        }.toList()
    }

    private fun categoryComboControls(html: String, excludedIds: Set<String>): List<ComboControl> {
        return parseComboControls(html)
            .filterNot { it.id in excludedIds || it.label.contains("줄수") }
            .filterNot { it.selectedKey == PAGE_SIZE_KEY && it.selectedLabel.contains("줄") }
            .sortedBy { it.sourceIndex }
    }

    private fun mergeControls(
        current: List<ComboControl>,
        updates: List<ComboControl>,
    ): List<ComboControl> {
        val updatesById = updates.associateBy { it.id }
        val currentIds = current.mapTo(mutableSetOf()) { it.id }
        return current.map { updatesById[it.id] ?: it } + updates.filterNot { it.id in currentIds }
    }

    private fun findLabel(html: String, controlId: String): String {
        return Regex(
            """<label\b[^>]*\bfor="$controlId"[^>]*>([\s\S]*?)</label>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.stripHtmlTags().orEmpty()
    }

    private fun dataValue(data: String, index: Int): String {
        return Regex("""(?:^|,)\s*$index:'([^']*)'""")
            .find(data)
            ?.groupValues
            ?.get(1)
            ?.decodeSapEscapes()
            .orEmpty()
    }

    private fun findTabStripId(html: String): String? {
        val firstTab = Regex(
            """<div\b[^>]*\bct="TSITM_standards"[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        return Regex(
            """<[^>]+(?=[^>]*\bid="([^"]+)")(?=[^>]*\bct="TS")[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html)
            .takeWhile { it.range.first < firstTab.range.first }
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?: Regex("""<div\b[^>]*\bid="([^"]+)-panel"[^>]*\brole="tablist""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
    }

    private fun findTab(html: String, category: CourseCatalogCategory): TabControl? {
        val regex = Regex(
            """<div\b(?=[^>]*\bct="TSITM_standards")(?=[^>]*\bid="([^"]+)")(?=[^>]*\blsdata="([^"]*)")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        return regex.findAll(html).map { match ->
            TabControl(
                id = match.groupValues[1],
                name = dataValue(match.groupValues[2], 2),
                selected = match.value.contains("Selected=\"true\"", ignoreCase = true),
            )
        }.firstOrNull { it.name == category.displayName }
    }

    private fun findButtonId(html: String, caption: String): String? {
        return Regex(
            """<(?:div|button)\b(?=[^>]*\bid="([^"]+)")(?=[^>]*\bct="B")[^>]*\blsdata="[^"]*0:'$caption'[^\"]*"[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
    }

    private fun findKeywordControlId(html: String): String? {
        val searchIndex = html.indexOf("0:'검색'", ignoreCase = true)
        return Regex(
            """<input\b(?=[^>]*\bid="([^"]+)")(?=[^>]*\btype="text")[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html)
            .takeWhile { searchIndex < 0 || it.range.first < searchIndex }
            .filterNot { match ->
                match.value.contains("ct=\"CB\"", ignoreCase = true) ||
                    match.value.contains("readonly", ignoreCase = true) ||
                    match.value.contains("disabled", ignoreCase = true)
            }
            .lastOrNull()
            ?.groupValues
            ?.get(1)
    }

    private fun findResultTableId(html: String): String? {
        val headerIndex = html.indexOf("강의계획서 유무")
            .takeIf { it >= 0 }
            ?: html.indexOf(">계획<").takeIf { it >= 0 }
            ?: return null
        return RESULT_TABLE_START_REGEX.findAll(html)
            .takeWhile { it.range.first < headerIndex }
            .lastOrNull()
            ?.groupValues
            ?.get(1)
    }

    private fun findResultTableRange(html: String): IntRange? {
        val tableId = findResultTableId(html) ?: return null
        val start = RESULT_TABLE_START_REGEX.findAll(html)
            .firstOrNull { it.groupValues[1] == tableId }
            ?: return null
        var depth = 0
        for (tag in TABLE_TAG_REGEX.findAll(html, start.range.first)) {
            if (tag.groupValues[1].isEmpty()) {
                depth++
            } else {
                depth--
                if (depth == 0) return start.range.first..tag.range.last
            }
        }
        return start.range.first..html.lastIndex
    }

    private fun parseTableRowCount(html: String, tableId: String): Int {
        val table = Regex(
            """<table\b(?=[^>]*\bid="$tableId")[^>]*\baria-rowcount="(\d+)"[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)
        return table?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0)
            ?: parseCourses(html).size
    }

    private fun parseTotalCount(html: String): Int {
        val markerIndexes = sequenceOf("조회", "총")
            .flatMap { marker -> html.indicesOf(marker) }
        return markerIndexes.firstNotNullOfOrNull { markerIndex ->
            val endExclusive = minOf(html.length, markerIndex + TOTAL_COUNT_WINDOW_SIZE)
            val text = html.substring(markerIndex, endExclusive).stripHtmlTags()
            TOTAL_COUNT_REGEXES.firstNotNullOfOrNull { regex ->
                regex.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            }
        } ?: 0
    }

    private fun String.indicesOf(value: String): Sequence<Int> = sequence {
        var startIndex = 0
        while (startIndex < length) {
            val index = indexOf(value, startIndex, ignoreCase = true)
            if (index < 0) break
            yield(index)
            startIndex = index + value.length
        }
    }

    private fun findNextPageButtonId(html: String): String? {
        val regex = Regex(
            """<(?:div|button)\b(?=[^>]*\bid="([^"]+)")(?=[^>]*\bct="B")[^>]*(?:title="[^"]*(?:다음|Next)[^"]*"|lsdata="[^"]*0:'[^']*(?:다음|Next)[^']*')[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        return regex.findAll(html)
            .firstOrNull { !it.value.contains("다음학기") && !isDisabled(it.value) }
            ?.groupValues
            ?.get(1)
    }

    private fun isDisabled(tag: String): Boolean {
        return tag.contains("aria-disabled=\"true\"", ignoreCase = true) ||
            tag.contains("lsButton--disabled", ignoreCase = true)
    }

    private fun extractNavigationUrl(html: String): String? {
        val href = Regex(
            """<a\b[^>]*\bhref="([^"]+)""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.decodeHtmlEntities()?.trim() ?: return null
        return when {
            href.startsWith("https://") || href.startsWith("http://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$ECC_ORIGIN$href"
            else -> null
        }
    }

    private fun comboEvent(id: String, key: String): String {
        return "ComboBox_Select~E002Id~E004$id~E005Key~E004${webDynpro.escape(key)}" +
            "~E005ByEnter~E004false~E003~E002ClientAction~E004submit" +
            "~E005ResponseData~E004delta~E003~E002~E003"
    }

    private fun tabEvent(tabStripId: String, itemId: String): String {
        return "TabStrip_TabSelect~E002Id~E004$tabStripId~E005ItemId~E004$itemId" +
            "~E003~E002ClientAction~E004submit~E005ResponseData~E004delta" +
            "~E003~E002~E003"
    }

    private fun inputChangeEvent(id: String, value: String): String {
        return "InputField_Change~E002Id~E004$id~E005Value~E004${webDynpro.escape(value)}" +
            "~E005ByEnter~E004false~E003~E002ClientAction~E004submit" +
            "~E005ResponseData~E004delta~E003~E002~E003"
    }

    private fun buttonEvent(id: String): String {
        return "Button_Press~E002Id~E004$id~E003~E002ClientAction~E004submit" +
            "~E005ResponseData~E004delta~E003~E002~E003"
    }

    private fun tableScrollEvent(id: String, firstVisibleRow: Int): String {
        return "Table_VerticalScroll~E002Id~E004$id~E005FirstVisibleItemIndex" +
            "~E004$firstVisibleRow~E005AccessType~E004SCROLLBAR~E003~E002ClientAction" +
            "~E004submit~E005ResponseData~E004delta~E003~E002~E003"
    }

    private suspend fun submit(
        context: WebDynproContext,
        event: String,
        focusId: String,
    ): WebDynproContext {
        return webDynpro.submitEvents(
            context,
            listOf(event, formRequest(focusId)).joinToString("~E001"),
        )
    }

    private fun formRequest(focusId: String): String {
        val focusInfo = webDynpro.escape("""{"sFocussedId":"$focusId"}""")
        return "Form_Request~E002Id~E004sap.client.SsrClient.form~E005Async~E004false" +
            "~E005FocusInfo~E004$focusInfo~E005Hash~E004~E005DomChanged~E004false" +
            "~E005IsDirty~E004false~E003~E002ResponseData~E004delta~E003~E002~E003"
    }

    private fun filterName(category: CourseCatalogCategory, index: Int): String {
        return FILTER_NAMES[category]?.getOrNull(index) ?: "필터 ${index + 1}"
    }

    private fun ComboControl.toPublicFilter(index: Int, name: String): CourseCatalogFilter {
        return CourseCatalogFilter(
            index = index,
            name = name,
            selectedKey = selectedKey,
            selectedLabel = selectedLabel,
            options = options,
        )
    }

    private fun CourseCatalogCourse.uniqueKey(): String {
        return listOf(subjectCode, section, professor, schedule).joinToString("|")
    }

    private fun MutableMap<String, CourseCatalogCourse>.addIfAbsent(
        course: CourseCatalogCourse,
    ) {
        val key = course.uniqueKey()
        val existing = this[key]
        if (existing == null || (existing.plan.isBlank() && course.plan.isNotBlank())) {
            this[key] = course
        }
    }

    private fun String.decodeSapEscapes(): String {
        return Regex("""\\x([0-9a-fA-F]{2,4})""").replace(this) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private data class ConfiguredContext(
        val context: WebDynproContext,
        val filters: List<ComboControl>,
        val keywordControlId: String?,
        val searchButtonId: String,
    )

    private data class ComboControl(
        val id: String,
        val sourceIndex: Int,
        val label: String,
        val selectedKey: String,
        val selectedLabel: String,
        val options: List<CourseCatalogFilterOption>,
    )

    private data class TabControl(
        val id: String,
        val name: String,
        val selected: Boolean,
    )

    private data class PageResult(
        val context: WebDynproContext,
        val nextButtonId: String?,
        val reportedCount: Int,
    )

    private data class ParsedCourse(
        val course: CourseCatalogCourse,
        val planButtonId: String?,
    )

    private data class CourseColumnSchema(
        val plan: Int,
        val primaryClassification: Int?,
        val multiMajorClassification: Int?,
        val engineeringCertification: Int?,
        val curriculumArea: Int?,
        val subjectCode: Int,
        val subjectName: Int,
        val registrationNotice: Int?,
        val courseType: Int?,
        val section: Int?,
        val professor: Int?,
        val department: Int?,
        val hoursCredits: Int?,
        val enrollmentCapacity: Int?,
        val remainingSeats: Int?,
        val schedule: Int?,
        val program: Int?,
        val targetStudents: Int?,
    ) {
        companion object {
            fun fromCells(cells: Map<Int, String>): CourseColumnSchema? {
                val lastIndex = cells.keys.maxOrNull() ?: return null
                val subjectIndex = cells.entries.firstOrNull { (index, html) ->
                    index in 1..MAX_SUBJECT_COLUMN_INDEX &&
                        html.stripHtmlTags().trim().matches(COURSE_CODE_REGEX)
                }?.key ?: return null
                val hasProgramColumn = lastIndex - subjectIndex >= COMMON_COLUMNS_WITH_PROGRAM
                val targetIndex = subjectIndex + if (hasProgramColumn) {
                    COMMON_COLUMNS_WITH_PROGRAM
                } else {
                    COMMON_COLUMNS_WITHOUT_PROGRAM
                }
                if (targetIndex > lastIndex || (subjectIndex..targetIndex).any { it !in cells }) {
                    return null
                }
                return CourseColumnSchema(
                    plan = 0,
                    primaryClassification = 1.takeIf { subjectIndex >= 2 },
                    multiMajorClassification = 2.takeIf { subjectIndex >= 4 },
                    engineeringCertification = 3.takeIf { subjectIndex >= 4 },
                    curriculumArea = 4.takeIf { subjectIndex >= 5 },
                    subjectCode = subjectIndex,
                    subjectName = subjectIndex + 1,
                    registrationNotice = subjectIndex + 2,
                    courseType = subjectIndex + 3,
                    section = subjectIndex + 4,
                    professor = subjectIndex + 5,
                    department = subjectIndex + 6,
                    hoursCredits = subjectIndex + 7,
                    enrollmentCapacity = subjectIndex + 8,
                    remainingSeats = subjectIndex + 9,
                    schedule = subjectIndex + 10,
                    program = (subjectIndex + 11).takeIf { hasProgramColumn },
                    targetStudents = targetIndex,
                )
            }
        }
    }

    private companion object {
        const val APP_NAME = "ZCMW2100"
        const val ECC_ORIGIN = "https://ecc.ssu.ac.kr:8443"
        const val URL =
            "$ECC_ORIGIN/sap/bc/webdynpro/SAP/$APP_NAME?sap-language=KO"
        const val PAGE_SIZE_KEY = "500"
        const val PAGE_SIZE = 500
        const val COMMON_COLUMNS_WITHOUT_PROGRAM = 11
        const val COMMON_COLUMNS_WITH_PROGRAM = 12
        const val MAX_SUBJECT_COLUMN_INDEX = 6
        const val MAX_PAGE_COUNT = 100
        const val TOTAL_COUNT_WINDOW_SIZE = 512

        val COURSE_ROW_REGEX =
            Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val COURSE_CELL_REGEX = Regex(
            """<td\b[^>]*\bcc="(\d+)"[^>]*>([\s\S]*?)</td>""",
            RegexOption.IGNORE_CASE,
        )
        val COURSE_HEADER_REGEX = Regex(
            """<th\b(?=[^>]*\brole="columnheader")[^>]*>([\s\S]*?)</th>""",
            RegexOption.IGNORE_CASE,
        )
        val WHITESPACE_REGEX = Regex("""\s+""")
        val COURSE_CODE_REGEX = Regex("""\d{8,}""")
        val RESULT_TABLE_START_REGEX = Regex(
            """<table\b(?=[^>]*\bid="([^"]+)")(?=[^>]*\bct="ST")[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val TABLE_TAG_REGEX = Regex("""<(/?)table\b[^>]*>""", RegexOption.IGNORE_CASE)
        val TOTAL_COUNT_REGEXES = listOf(
            Regex("""총\s*([\d,]+)\s*(?:건|개|과목)"""),
            Regex("""조회\s*건수\s*[:：]?\s*([\d,]+)"""),
        )

        val KEYWORD_CATEGORIES = setOf(
            CourseCatalogCategory.PROFESSOR,
            CourseCatalogCategory.SUBJECT,
        )

        val FILTER_NAMES = mapOf(
            CourseCatalogCategory.DEPARTMENT to listOf("대학", "학부/학과", "전공"),
            CourseCatalogCategory.REQUIRED_GENERAL to listOf("교양필수 구분", "세부 구분"),
            CourseCatalogCategory.ELECTIVE_GENERAL to listOf("교양 영역", "세부 영역"),
            CourseCatalogCategory.CHAPEL to listOf("채플 구분"),
            CourseCatalogCategory.TEACHING to listOf("교직 구분"),
            CourseCatalogCategory.GRADUATE to listOf("대학원", "학과", "전공"),
            CourseCatalogCategory.LINKED_MAJOR to listOf("연계전공"),
            CourseCatalogCategory.CONVERGENCE_MAJOR to listOf("융합전공"),
            CourseCatalogCategory.CROSS_MAJOR to listOf("대학", "학부/학과", "인정 전공"),
            CourseCatalogCategory.CYBER_UNIVERSITY to listOf("과정 구분"),
        )
    }
}
