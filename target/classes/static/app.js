const { createApp, ref, reactive, computed, nextTick } = Vue;

const api = {
    async get(url) {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error((await response.text()) || response.statusText);
        }
        return response.json();
    },
    async post(url, body) {
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {})
        });
        if (!response.ok) {
            throw new Error((await response.text()) || response.statusText);
        }
        return response.json();
    }
};

function normalizeList(data) {
    if (!data) return [];
    if (Array.isArray(data.list)) return data.list;
    if (data.data && Array.isArray(data.data.list)) return data.data.list;
    if (Array.isArray(data.videoList)) return data.videoList;
    return [];
}

function normalizeClasses(data) {
    if (!data) return [];
    if (Array.isArray(data.class)) return data.class;
    if (Array.isArray(data.classes)) return data.classes;
    if (data.classes && Array.isArray(data.classes.sortList)) return data.classes.sortList;
    return [];
}

function toVideoCard(raw) {
    return {
        id: raw.vod_id || raw.id,
        name: raw.vod_name || raw.name || '未命名',
        pic: raw.vod_pic || raw.pic || '',
        remark: raw.vod_remarks || raw.note || raw.remarks || '',
        sourceKey: raw.source_uid || raw.source_key || raw.sourceKey || '',
        sourceName: raw.source_name || raw.sourceName || ''
    };
}

function sanitizePlayableUrl(input) {
    if (!input) return input;
    const raw = String(input).trim();
    const match = raw.match(/^[^,，]{1,30}[,，](.+)$/);
    if (!match) return raw;
    const candidate = (match[1] || '').trim();
    if (/^(https?:\/\/|rtmp:\/\/|rtsp:\/\/|ftp:\/\/|magnet:|thunder:|ed2k:\/\/|\/\/)/i.test(candidate)) {
        return candidate;
    }
    return raw;
}

function parsePlayInfo(vod) {
    const from = (vod.vod_play_from || '').split('$$$').filter(Boolean);
    const urls = (vod.vod_play_url || '').split('$$$').filter(Boolean);
    const byFlag = {};
    from.forEach((flag, idx) => {
        const line = urls[idx] || '';
        byFlag[flag] = line
            .split('#')
            .map(item => item.trim())
            .filter(Boolean)
            .map(item => {
                const [name, id] = item.includes('$') ? item.split('$') : [item, item];
                return { name, id: sanitizePlayableUrl(id) };
            });
    });
    return { flags: from, byFlag };
}

createApp({
    setup() {
        const defaultCover = 'https://dummyimage.com/400x560/1b2650/8ea2d8&text=TVBox';
        const configUrl = ref('');
        const keyword = ref('');
        const config = ref(null);
        const sources = ref([]);
        const selectedSource = ref('');
        const classes = ref([]);
        const activeClassId = ref('');
        const videos = ref([]);
        const listTitle = ref('片单');
        const playerRef = ref(null);

        const status = reactive({ text: '等待加载配置…', ok: false, error: false });
        const loading = reactive({ config: false, home: false, search: false, detail: false, play: false });

        const favorites = ref(JSON.parse(localStorage.getItem('tvbox_favorites') || '[]'));
        const history = ref(JSON.parse(localStorage.getItem('tvbox_history') || '[]'));

        const player = reactive({ url: '', info: '' });

        const detailVisible = ref(false);
        const detail = reactive({
            name: '',
            meta: '',
            desc: '',
            sourceKey: '',
            flags: [],
            selectedFlag: '',
            episodesByFlag: {}
        });

        const sourceCount = computed(() => sources.value.length);
        const currentEpisodes = computed(() => detail.episodesByFlag[detail.selectedFlag] || []);

        function setStatus(text, ok = false, error = false) {
            status.text = text;
            status.ok = ok;
            status.error = error;
        }

        function saveStorage() {
            localStorage.setItem('tvbox_favorites', JSON.stringify(favorites.value.slice(0, 200)));
            localStorage.setItem('tvbox_history', JSON.stringify(history.value.slice(0, 100)));
        }

        function getClassId(cls) {
            return String(cls.type_id || cls.typeId || cls.id || '');
        }

        function getClassName(cls) {
            return cls.type_name || cls.typeName || cls.name || '未命名分类';
        }

        function getSourceNameByKey(sourceKey) {
            const site = sources.value.find(item => item.uid === sourceKey || item.api === sourceKey || item.key === sourceKey || item.name === sourceKey);
            return site?.name || sourceKey || '未知';
        }

        async function safeRun(fn, loadingKey, failPrefix) {
            try {
                if (loadingKey) {
                    loading[loadingKey] = true;
                }
                await fn();
            } catch (error) {
                setStatus(`${failPrefix}：${error.message}`, false, true);
            } finally {
                if (loadingKey) {
                    loading[loadingKey] = false;
                }
            }
        }

        async function loadConfig() {
            await safeRun(async () => {
                if (!configUrl.value) {
                    setStatus('请先输入配置 URL', false, true);
                    return;
                }
                setStatus('正在加载配置...');
                const data = await api.post('/api/config/load', { url: configUrl.value });
                config.value = data.config;
                sources.value = data.config.sites || [];
                selectedSource.value = sources.value[0]?.uid || sources.value[0]?.api || sources.value[0]?.key || '';
                setStatus(`配置加载成功，站点 ${data.summary?.sites || sources.value.length} 个`, true, false);
            }, 'config', '加载失败');
        }

        async function loadHome() {
            await safeRun(async () => {
                if (!selectedSource.value) {
                    setStatus('请先选择站点源', false, true);
                    return;
                }
                setStatus('加载首页中...');
                const data = await api.get(`/api/source/${encodeURIComponent(selectedSource.value)}/home?filter=true`);
                classes.value = normalizeClasses(data);
                activeClassId.value = '';
                videos.value = normalizeList(data).map(item => toVideoCard({
                    ...item,
                    source_key: selectedSource.value,
                    source_name: getSourceNameByKey(selectedSource.value)
                }));
                listTitle.value = '首页推荐';
                setStatus('首页加载完成', true, false);
            }, 'home', '首页失败');
        }

        async function loadCategory(tid, page = 1) {
            await safeRun(async () => {
                if (!selectedSource.value) {
                    setStatus('请先选择站点源', false, true);
                    return;
                }
                activeClassId.value = String(tid);
                setStatus('加载分类中...');
                const data = await api.post(`/api/source/${encodeURIComponent(selectedSource.value)}/category`, {
                    tid,
                    pg: String(page),
                    filter: true,
                    extend: {}
                });
                videos.value = normalizeList(data).map(item => toVideoCard({
                    ...item,
                    source_key: selectedSource.value,
                    source_name: getSourceNameByKey(selectedSource.value)
                }));
                listTitle.value = `分类：${tid}`;
                setStatus(`分类加载完成：${tid}`, true, false);
            }, 'home', '分类失败');
        }

        async function doSearch() {
            await safeRun(async () => {
                if (!sources.value.length) {
                    setStatus('请先加载配置', false, true);
                    return;
                }
                if (!keyword.value) {
                    setStatus('请输入搜索关键词', false, true);
                    return;
                }
                setStatus(`全源搜索：${keyword.value}`);
                const data = await api.get(`/api/search/all?wd=${encodeURIComponent(keyword.value)}&quick=false`);
                videos.value = normalizeList(data).map(toVideoCard);
                listTitle.value = `全源搜索：${keyword.value}`;
                setStatus(`搜索完成：命中 ${data.hits || videos.value.length} 条，失败 ${data.failed || 0} 个源`, true, false);
            }, 'search', '搜索失败');
        }

        async function loadDetail(id, sourceKey, item) {
            await safeRun(async () => {
                const resolvedSourceKey = sourceKey || selectedSource.value;
                if (!resolvedSourceKey) {
                    setStatus('缺少来源信息，无法加载详情', false, true);
                    return;
                }
                setStatus('加载详情中...');
                const data = await api.get(`/api/source/${encodeURIComponent(resolvedSourceKey)}/detail?id=${encodeURIComponent(id)}`);
                const list = normalizeList(data);
                if (!list.length) {
                    const fallbackVod = {
                        vod_id: id,
                        vod_name: item?.name || `资源 ${id}`,
                        vod_remarks: item?.remark || '',
                        vod_content: '该源未返回标准详情，已启用通用详情兜底。',
                        vod_play_from: '默认',
                        vod_play_url: `立即播放$${id}`
                    };
                    const parsedFallback = parsePlayInfo(fallbackVod);
                    detail.name = fallbackVod.vod_name;
                    detail.meta = [fallbackVod.vod_remarks, getSourceNameByKey(resolvedSourceKey)].filter(Boolean).join(' · ');
                    detail.desc = fallbackVod.vod_content;
                    detail.sourceKey = resolvedSourceKey;
                    detail.flags = parsedFallback.flags;
                    detail.selectedFlag = parsedFallback.flags[0] || '默认';
                    detail.episodesByFlag = parsedFallback.byFlag;
                    if (!detail.episodesByFlag[detail.selectedFlag]) {
                        detail.episodesByFlag[detail.selectedFlag] = [{ name: '立即播放', id }];
                    }
                    detailVisible.value = true;
                    setStatus('详情已使用兜底模式打开', true, false);
                    return;
                }

                const vod = list[0];
                const parsed = parsePlayInfo(vod);
                detail.name = vod.vod_name || vod.name || '详情';
                detail.meta = [vod.type_name, vod.vod_year, vod.vod_area, vod.vod_remarks].filter(Boolean).join(' · ');
                detail.desc = vod.vod_content || '';
                detail.sourceKey = resolvedSourceKey;
                detail.flags = parsed.flags;
                detail.selectedFlag = parsed.flags[0] || '';
                detail.episodesByFlag = parsed.byFlag;
                detailVisible.value = true;

                setStatus('详情加载完成', true, false);
            }, 'detail', '详情失败');
        }

        async function playEpisode(flag, id, epName) {
            await safeRun(async () => {
                const resolvedSourceKey = detail.sourceKey || selectedSource.value;
                if (!resolvedSourceKey) {
                    setStatus('缺少来源信息，无法播放', false, true);
                    return;
                }
                setStatus(`解析播放：${epName}`);
                const data = await api.get(`/api/source/${encodeURIComponent(resolvedSourceKey)}/play?flag=${encodeURIComponent(flag)}&id=${encodeURIComponent(id)}`);

                let finalUrl = sanitizePlayableUrl(data.url || id);
                const parse = Number(data.parse || 0);
                if (parse === 1 && data.playUrl) {
                    finalUrl = `${data.playUrl}${finalUrl}`;
                }
                finalUrl = sanitizePlayableUrl(finalUrl);

                player.url = finalUrl;
                player.info = `当前：${epName} | 源：${flag}`;
                history.value.unshift({
                    sourceKey: resolvedSourceKey,
                    flag,
                    id,
                    name: epName,
                    playUrl: finalUrl,
                    ts: Date.now()
                });
                saveStorage();
                detailVisible.value = false;

                await nextTick();
                if (playerRef.value) {
                    playerRef.value.src = finalUrl;
                    playerRef.value.play().catch(() => {});
                }

                setStatus(`已开始播放：${epName}`, true, false);
            }, 'play', '播放失败');
        }

        function addFavorite(video) {
            favorites.value.unshift({
                ...video,
                sourceKey: video.sourceKey || selectedSource.value,
                sourceName: video.sourceName || getSourceNameByKey(video.sourceKey || selectedSource.value),
                ts: Date.now()
            });
            saveStorage();
            setStatus(`已收藏：${video.name}`, true, false);
        }

        function showFavorites() {
            videos.value = favorites.value.map(item => toVideoCard({
                vod_id: item.id,
                vod_name: item.name,
                vod_pic: item.pic,
                vod_remarks: item.remark,
                source_key: item.sourceKey,
                source_name: item.sourceName
            }));
            listTitle.value = '收藏列表';
            setStatus(`已展示收藏：${favorites.value.length} 条`, true, false);
        }

        function showHistory() {
            videos.value = history.value.map(item => ({
                id: item.id,
                name: item.name,
                pic: defaultCover,
                remark: new Date(item.ts).toLocaleString(),
                sourceKey: item.sourceKey,
                sourceName: getSourceNameByKey(item.sourceKey)
            }));
            listTitle.value = '播放历史';
            setStatus(`已展示历史：${history.value.length} 条`, true, false);
        }

        function manualPlay() {
            if (!player.url) {
                return;
            }
            if (playerRef.value) {
                playerRef.value.src = player.url;
                playerRef.value.play().catch(() => {});
                setStatus('已开始手动播放', true, false);
            }
        }

        function closeDetail() {
            detailVisible.value = false;
        }

        function loadCategoryByClass(cls) {
            const classId = getClassId(cls);
            if (classId) {
                loadCategory(classId, 1);
            }
        }

        return {
            defaultCover,
            configUrl,
            keyword,
            sources,
            selectedSource,
            classes,
            activeClassId,
            videos,
            listTitle,
            player,
            playerRef,
            favorites,
            history,
            sourceCount,
            status,
            loading,
            detailVisible,
            detail,
            currentEpisodes,
            getClassId,
            getClassName,
            loadConfig,
            loadHome,
            doSearch,
            loadDetail,
            playEpisode,
            addFavorite,
            showFavorites,
            showHistory,
            manualPlay,
            closeDetail,
            loadCategoryByClass
        };
    }
}).mount('#app');
