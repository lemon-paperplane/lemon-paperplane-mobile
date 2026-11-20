package cn.lemonit.paperplane.features.files

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.fsGrep
import cn.lemonit.paperplane.kit.fsList
import cn.lemonit.paperplane.kit.fsSearch
import cn.lemonit.paperplane.kit.getProject
import cn.lemonit.paperplane.kit.model.FSEntry
import cn.lemonit.paperplane.kit.model.FSGrepMatch
import cn.lemonit.paperplane.kit.model.FSGrepResult
import cn.lemonit.paperplane.kit.model.FSListResult
import cn.lemonit.paperplane.kit.model.FSSearchHit
import cn.lemonit.paperplane.kit.vcsStatus
import kotlinx.coroutines.CancellationException

/** 搜索方式：按文件名（fs.search）/ 在文件内容里找（fs.grep） */
internal enum class FileSearchMode { NAME, CONTENT }

/**
 * 「文件」标签的数据（对照 Mac FileTreeModel）：手机上一次只显示一个目录（面包屑导航），
 * 已经看过的目录缓存在内存里，回来时先显示缓存再刷新。Git 状态取工程各仓库的 vcs.status。
 */
internal class FilesState(val projectId: String) {
    /** 目录（"" 为工程根）→ 内容 */
    val listings = mutableStateMapOf<String, FSListResult>()
    val loading = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>()

    /** 相对工程根的路径 → Git 状态字母（M A D R C U ? T） */
    var git by mutableStateOf<Map<String, String>>(emptyMap())
        internal set
    /** 含有改动的目录 */
    var changedDirs by mutableStateOf<Set<String>>(emptySet())
        internal set

    var showHidden by mutableStateOf(prefs()?.getBoolean(KEY_HIDDEN, true) ?: true)
        private set

    // 搜索
    var searching by mutableStateOf(false)
    var mode by mutableStateOf(FileSearchMode.NAME)
    var query by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var nameHits by mutableStateOf<List<FSSearchHit>?>(null)
        internal set
    var grep by mutableStateOf<FSGrepResult?>(null)
        internal set
    var searchBusy by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    fun toggleHidden() {
        showHidden = !showHidden
        prefs()?.edit()?.putBoolean(KEY_HIDDEN, showHidden)?.apply()
    }

    fun visible(dir: String): List<FSEntry>? =
        listings[dir]?.entries?.let { list -> if (showHidden) list else list.filter { !it.hidden } }

    suspend fun load(dir: String) {
        loading[dir] = true
        try {
            val r = AppModel.api().fsList(projectId, dir)
            listings[dir] = r
            errors.remove(dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors[dir] = e.message ?: ""
        } finally {
            loading.remove(dir)
        }
    }

    /** 刷新当前目录和 Git 状态；看过的其他目录丢掉缓存（回去时重新读） */
    suspend fun refresh(dir: String) {
        for (k in listings.keys.toList()) if (k != dir) listings.remove(k)
        load(dir)
        loadGit()
    }

    suspend fun loadGit() {
        val api = runCatching { AppModel.api() }.getOrNull() ?: return
        val roots = runCatching { api.getProject(projectId).vcsRoots }.getOrNull().orEmpty()
        if (roots.isEmpty()) {
            git = emptyMap()
            changedDirs = emptySet()
            return
        }
        val map = HashMap<String, String>()
        for (root in roots) {
            val st = runCatching { api.vcsStatus(projectId, root.path) }.getOrNull() ?: continue
            for (f in st.files) {
                val rel = if (root.relPath.isEmpty()) f.path else root.relPath + "/" + f.path
                map[rel] = if (f.conflicted) "U" else f.status
            }
        }
        val dirs = HashSet<String>()
        for ((p, s) in map) {
            if (s == "D") continue
            var d = FileActions.parent(p)
            while (d.isNotEmpty() && dirs.add(d)) d = FileActions.parent(d)
        }
        git = map
        changedDirs = dirs
    }

    fun clearSearch() {
        query = ""
        nameHits = null
        grep = null
        searchError = null
        searchBusy = false
    }

    /** 执行一次搜索（调用方负责防抖与取消） */
    suspend fun search() {
        val q = query.trim()
        if (q.isEmpty()) {
            nameHits = null
            grep = null
            searchError = null
            searchBusy = false
            return
        }
        searchBusy = true
        searchError = null
        try {
            when (mode) {
                FileSearchMode.NAME -> nameHits = AppModel.api().fsSearch(projectId, q, limit = 200)
                FileSearchMode.CONTENT -> grep = AppModel.api().fsGrep(projectId, q, regex = regex, caseSensitive = caseSensitive, limit = 500)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = e.message ?: ""
            if (mode == FileSearchMode.NAME) nameHits = emptyList() else grep = FSGrepResult()
        } finally {
            searchBusy = false
        }
    }

    companion object {
        private const val KEY_HIDDEN = "showHidden"

        private fun prefs() = runCatching { AppModel.context.getSharedPreferences("paperplane_files", Context.MODE_PRIVATE) }.getOrNull()

        /** 内容搜索结果按文件分组（保持内核返回的顺序） */
        fun group(matches: List<FSGrepMatch>): List<Pair<String, List<FSGrepMatch>>> {
            val out = LinkedHashMap<String, MutableList<FSGrepMatch>>()
            for (m in matches) out.getOrPut(m.path) { mutableListOf() } += m
            return out.map { it.key to it.value }
        }

        /** 面包屑：("" 根), "a", "a/b" … */
        fun crumbs(dir: String): List<Pair<String, String>> {
            if (dir.isEmpty()) return emptyList()
            val parts = dir.split('/').filter { it.isNotEmpty() }
            return parts.indices.map { i -> parts[i] to parts.subList(0, i + 1).joinToString("/") }
        }
    }
}
