package org.koitharu.kotatsu.parsers.site.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAKIO", "MangaKIO", "en", ContentType.MANGA)
internal class MangaK(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAKIO, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("mangak.io")

	private val apiUrl = "https://api.mangak.io"

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

	// Pulls the chapter number (incl. a fractional part) out of a title like "Chapter 102.5".
	private val chapterNumberRegex = Regex("""chapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

	// Per-source toggle: when off (default) adult titles are hidden from lists and search.
	private val showNsfwKey = ConfigKey.ShowNsfwContent(false)

	// Genre slugs/titles that mark a title as adult on MangaKIO.
	private val nsfwTags = setOf(
		"adult", "adult-content", "mature", "smut", "hentai", "erotica", "ecchi", "doujinshi", "soft-yaoi",
	)

	private fun Set<MangaTag>.hasNsfw(): Boolean = any {
		it.key.lowercase(Locale.US) in nsfwTags || it.title.lowercase(Locale.US) in nsfwTags
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(showNsfwKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	private suspend fun fetchTags(): Set<MangaTag> {
		val json = webClient.httpGet("$apiUrl/genres").parseJson()
		return json.getJSONObject("data").getJSONArray("items").mapJSONToSet { item ->
			MangaTag(
				title = item.getString("name").toTitleCase(sourceLocale),
				key = item.getString("slug"),
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append(apiUrl)
			append("/titles/search?page=")
			append(page)
			append("&limit=")
			append(pageSize)
			append("&sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "latest"
					SortOrder.NEWEST -> "newest"
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "rating"
					else -> "latest"
				},
			)
			if (!filter.query.isNullOrEmpty()) {
				append("&q=")
				append(filter.query.urlEncoded())
			}
			if (filter.tags.isNotEmpty()) {
				append("&genres=")
				append(filter.tags.joinToString(",") { it.key })
			}
			if (filter.tagsExclude.isNotEmpty()) {
				append("&exclude=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}
			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						else -> "all"
					},
				)
			}
		}
		val json = webClient.httpGet(url).parseJson()
		val list = json.getJSONObject("data").getJSONArray("items").mapJSON { item ->
			item.toManga()
		}
		// Filter on tags (not contentRating). Marking these as ContentRating.ADULT would make the
		// app's global "Hide NSFW content" setting strip them even when this toggle is on, so the
		// per-source toggle would never win. Keeping them un-rated makes this toggle authoritative.
		return if (config[showNsfwKey]) {
			list
		} else {
			list.filterNot { it.tags.hasNsfw() }
		}
	}

	private fun JSONObject.toManga(): Manga {
		val relativeUrl = getString("url")
		val tags = optJSONArray("genres")?.mapJSONNotNullToSet { genre ->
			val slug = genre.optString("slug").nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(genre.getString("name").toTitleCase(sourceLocale), slug, source)
		}.orEmpty()
		return Manga(
			id = generateUid(getString("id")),
			title = getString("name"),
			altTitles = emptySet(),
			url = getString("id"),
			publicUrl = "https://$domain$relativeUrl",
			rating = optDouble("rating", 0.0).toRating(),
			// Left unrated on purpose — the per-source "Show NSFW" toggle governs visibility (see getListPage).
			contentRating = null,
			coverUrl = optString("cover").nullIfEmpty(),
			tags = tags,
			state = optString("status").toMangaState(),
			authors = emptySet(),
			description = optString("summary").nullIfEmpty(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val json = webClient.httpGet("$apiUrl/titles/${manga.url}").parseJson()
			.getJSONObject("data").getJSONObject("title")
		val cv = json.optLong("cv")
		val authors = json.optJSONArray("authors")?.mapJSONNotNullToSet {
			it.optString("name").nullIfEmpty()
		}.orEmpty()
		val artists = json.optJSONArray("artists")?.mapJSONNotNullToSet {
			it.optString("name").nullIfEmpty()
		}.orEmpty()
		val tags = json.optJSONArray("genres")?.mapJSONNotNullToSet { genre ->
			val slug = genre.optString("slug").nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(genre.getString("name").toTitleCase(sourceLocale), slug, source)
		}.orEmpty()
		val altTitles = json.optString("alt_name").split(";")
			.mapNotNull { it.trim().nullIfEmpty() }
			.toSet()
		val finalTags = tags.ifEmpty { manga.tags }
		return manga.copy(
			title = json.optString("name").nullIfEmpty() ?: manga.title,
			altTitles = altTitles,
			description = json.optString("summary").nullIfEmpty() ?: manga.description,
			tags = finalTags,
			contentRating = manga.contentRating,
			authors = authors + artists,
			state = json.optString("status").toMangaState() ?: manga.state,
			rating = json.optDouble("rating", 0.0).toRating(),
			chapters = fetchChapters(manga.url, cv),
		)
	}

	private suspend fun fetchChapters(id: String, cv: Long): List<MangaChapter> {
		val json = webClient.httpGet("$apiUrl/titles/$id/chapters?cv=$cv").parseJson()
		val chapters = json.getJSONObject("data").getJSONArray("chapters").mapJSON { it }
		return chapters.mapIndexed { index, chapter ->
			// The real chapter number lives in the title ("Chapter 102.5") — the API's chapter_number
			// is always null and its "number" field is just a sequential index. Parse it out so the
			// chapter badge, ordering and the app's "hide partial chapters" filter work. Specials and
			// extras that don't match are left unnumbered (0) rather than given a fabricated number.
			val title = chapter.optString("name").nullIfEmpty()
			MangaChapter(
				id = generateUid(chapter.getString("id")),
				title = title,
				number = title?.let { chapterNumberRegex.find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f,
				volume = 0,
				url = chapter.getString("url"),
				scanlator = null,
				uploadDate = dateFormat.parseSafe(chapter.optString("updated_at").substringBefore('.')),
				branch = null,
				source = source,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
		val raw = doc.selectFirst("script#__NEXT_DATA__")?.data()
			?: throw ParseException("Cannot find chapter data", chapter.url)
		val images = JSONObject(raw)
			.getJSONObject("props")
			.getJSONObject("pageProps")
			.getJSONObject("initialChapter")
			.getJSONArray("images")
		return (0 until images.length()).map { i ->
			val imageUrl = images.getString(i)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun Double.toRating(): Float = if (this > 0.0) (this / 5.0).toFloat() else RATING_UNKNOWN

	private fun String?.toMangaState(): MangaState? = when (this?.lowercase(Locale.US)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		else -> null
	}
}
