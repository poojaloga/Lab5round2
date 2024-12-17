package com.codepath.articlesearch

import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.codepath.articlesearch.databinding.ActivityMainBinding
import com.codepath.asynchttpclient.AsyncHttpClient
import com.codepath.asynchttpclient.callback.JsonHttpResponseHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.json.JSONException

fun createJson() = Json {
    isLenient = true
    ignoreUnknownKeys = true
    useAlternativeNames = false
}

private const val TAG = "MainActivity/"
private const val SEARCH_API_KEY = BuildConfig.API_KEY
private const val ARTICLE_SEARCH_URL =
    "https://api.nytimes.com/svc/search/v2/articlesearch.json?api-key=${SEARCH_API_KEY}"

class MainActivity : AppCompatActivity() {
    private lateinit var articlesRecyclerView: RecyclerView
    private lateinit var swipeContainer: SwipeRefreshLayout
    private lateinit var searchView: SearchView
    private lateinit var binding: ActivityMainBinding
    private val articles = mutableListOf<DisplayArticle>()
    private lateinit var networkReceiver: NetworkReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        articlesRecyclerView = findViewById(R.id.articles)
        swipeContainer = findViewById(R.id.swipeContainer)
        searchView = findViewById(R.id.searchView)

        val articleAdapter = ArticleAdapter(this, articles)
        articlesRecyclerView.adapter = articleAdapter

        articlesRecyclerView.layoutManager = LinearLayoutManager(this).also {
            val dividerItemDecoration = DividerItemDecoration(this, it.orientation)
            articlesRecyclerView.addItemDecoration(dividerItemDecoration)
        }

        swipeContainer.setOnRefreshListener {
            fetchArticles()
        }

        swipeContainer.setColorSchemeResources(
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_blue_bright,
            android.R.color.holo_red_light
        )

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    fetchArticles(it)
                }
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })

        lifecycleScope.launch {
            (application as ArticleApplication).db.articleDao().getAll().collect { databaseList ->
                databaseList.map { entity ->
                    DisplayArticle(
                        entity.headline,
                        entity.articleAbstract,
                        entity.byline,
                        entity.mediaImageUrl
                    )
                }.also { mappedList ->
                    articles.clear()
                    articles.addAll(mappedList)
                    articleAdapter.notifyDataSetChanged()
                }
            }
        }
        fetchArticles()

        networkReceiver = NetworkReceiver()
        val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(networkReceiver, intentFilter)
    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(networkReceiver)
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    fun fetchArticles(query: String = "") {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val shouldCache = sharedPreferences.getBoolean("cache_data", true)

        val url = if (query.isEmpty()) {
            ARTICLE_SEARCH_URL
        } else {
            "https://api.nytimes.com/svc/search/v2/articlesearch.json?q=$query&api-key=${SEARCH_API_KEY}"
        }
        val client = AsyncHttpClient()
        client.get(url, object : JsonHttpResponseHandler() {
            override fun onFailure(
                statusCode: Int,
                headers: Headers?,
                response: String?,
                throwable: Throwable?

            ) {
                Log.e(TAG, "Failed to fetch articles: $statusCode")
                swipeContainer.isRefreshing = false
            }

            override fun onSuccess(statusCode: Int, headers: Headers, json: JSON) {
                Log.i(TAG, "Successfully fetched articles: $json")
                try {
                    val parsedJson = createJson().decodeFromString(
                        SearchNewsResponse.serializer(),
                        json.jsonObject.toString()
                    )
                    parsedJson.response?.docs?.let { list ->
                        if (shouldCache) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                (application as ArticleApplication).db.articleDao().deleteAll()
                                (application as ArticleApplication).db.articleDao().insertAll(list.map {
                                    ArticleEntity(
                                        headline = it.headline?.main,
                                        articleAbstract = it.abstract,
                                        byline = it.byline?.original,
                                        mediaImageUrl = it.mediaImageUrl
                                    )
                                })
                                withContext(Dispatchers.Main) {
                                    swipeContainer.isRefreshing = false
                                }
                            }
                        } else {
                            swipeContainer.isRefreshing = false
                        }
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Exception: $e")
                    swipeContainer.isRefreshing = false
                }
            }
        }
        )
    }
}
