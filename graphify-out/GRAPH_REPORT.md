# Graph Report - pocket-llm-server-android  (2026-10-09)

## Corpus Check
- 241 files · ~169,907 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 57 file(s) not represented in the graph (top: .xml 30, .ttf 10, (none) 8)

## Summary
- 3632 nodes · 11631 edges · 193 communities (125 shown, 68 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 527 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `1de9ca76`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- main-K5DSW5YL.js
- .pipe
- SkillManagerBottomSheet.kt
- materialtheme
- cs
- MobileActionsScreen.kt
- AppModule.kt
- Config
- MessageInputText.kt
- e
- get
- BenchmarkViewModel
- HomeScreen.kt
- ModelManagerViewModel
- xd
- ChatMessage.kt
- j
- constructor
- Model
- ConfigDialog.kt
- Text
- GalleryNavGraph.kt
- LlmSingleTurnViewModel
- customcolors
- MainActivity.kt
- SkillManagerViewModel
- ChatPanel
- PromptTemplateConfigs.kt
- ModelManagerViewModel.kt
- forEach
- MainUi
- AgentTools.kt
- Actions.kt
- DownloadWorker.kt
- YS
- EmptyState
- DownloadAndTryButton.kt
- HoldToDictateViewModel
- gallery/common/Utils.kt
- AgentChatScreen
- TinyGardenTools.kt
- PocketNodeServer.kt
- has
- GalleryWebView.kt
- vy
- SkillManagerViewModel.kt
- DataStoreRepository
- DefaultDataStoreRepository
- BenchmarkResultsViewer.kt
- FcmMessagingService.kt
- log
- ui/common/Utils.kt
- MobileActionsViewModel
- TinyGardenViewModel.kt
- GlobalModelManager
- run_js tool
- vi
- MessageInputText
- SkillManagerBottomSheet
- MobileActionsTask.kt
- Active Allowlist 1_0_11.json (.litertlm only)
- GalleryTopAppBar
- PocketNodeServer
- ChatMessageType
- mood-tracker ai_edge_gallery_get_result
- LiteRtLmMapper.kt
- consumeOptional
- AgentChatTaskModule.kt
- AudioDecoderHelper.kt
- LlmSingleTurnTaskModule.kt
- LlmModelHelper
- Context
- ExampleCustomTask.kt
- MobileActionsViewModel.kt
- TinyGardenTask.kt
- AudioPlaybackPanel
- Task
- AI Edge Gallery Agent Skills
- DownloadRepository.kt
- .addEventListener
- ScriptsTabContent
- CustomTask
- DownloadAndTryButton
- .notify
- SkillTesterBottomSheet
- HomeScreen
- OpenAiModels.kt
- .subscribe
- ResponseUtilsTest
- mood-tracker ai_edge_gallery_get_result (action dispatcher)
- restaurant-roulette Skill Definition
- file
- startRecording
- ai_edge_gallery_get_result JS Skill Contract
- jt
- PocketNodeServerDialog
- SensorObserver
- mood-music Skill Definition
- .compileModuleAndAllComponentsSync
- ChatRequestParserTest
- Inject
- activateRoutes
- ChatRequestParser.kt
- AddSkillOptionType
- ActionType
- MobileActionsModule.kt
- TinyGardenTaskModule.kt
- ConfigValue
- SettingsDialog
- TosViewModel
- .setupNavigations
- ExampleCustomTaskModule.kt
- 88-Key Piano Keyboard Generator
- getJsonResponse
- w
- LlmAskAudioModule
- GalleryApp.kt
- PocketNodeService
- MessageLatency.kt
- ParsedTurn
- MessageBubbleShape.kt
- wp
- Android Full Bug Report Capture Guide
- init
- HuggingFace OAuth Developer Application
- kitchen-adventure Skill
- Theme.kt
- toString
- .getBaseHrefFromDOM
- getTaskBgGradientColors
- yy
- kitchen-adventure Skill
- Feature Request Issue Template
- Support Request Issue Template
- Contribution Policy (not accepting code contributions)
- Bo
- MainUi
- t
- r
- BenchmarkResultsViewer
- destroy
- embeddedTView
- o
- Wo
- n
- AudioAnimation.kt
- insertImpl
- L
- Mn
- find
- DataCard
- GalleryNavHost
- Pocket Node — OpenAI-compatible REST API
- run
- bn
- ct
- Gc
- kd
- LanguageType
- I
- Wf
- RewriteToneType
- Aggregation
- TextAndVoiceInput
- indexOf
- BenchmarkResultInfo
- .createRenderer
- di
- rd
- iu
- CustomTaskData
- InputEditorLabel
- createSegments
- BenchmarkModelPicker
- runTask
- Dg

## God Nodes (most connected - your core abstractions)
1. `e` - 208 edges
2. `Model` - 186 edges
3. `ModelManagerViewModel` - 139 edges
4. `Task` - 111 edges
5. `Text` - 101 edges
6. `n()` - 76 edges
7. `t()` - 75 edges
8. `constructor()` - 64 edges
9. `DataStoreRepository` - 53 edges
10. `r()` - 51 edges

## Surprising Connections (you probably didn't know these)
- ``POST /v1/chat/completions`` --references--> `length()`  [INFERRED]
  docs/OPENAI_API.md → Android/src/app/src/main/assets/tinygarden/main-K5DSW5YL.js
- ``POST /v1/chat/completions`` --references--> `n()`  [INFERRED]
  docs/OPENAI_API.md → Android/src/app/src/main/assets/tinygarden/main-K5DSW5YL.js
- ``POST /v1/chat/completions`` --references--> `AudioDecoderHelper`  [INFERRED]
  docs/OPENAI_API.md → Android/src/app/src/main/java/com/google/ai/edge/gallery/server/AudioDecoderHelper.kt
- `Pocket Node — OpenAI-compatible REST API` --references--> `PocketNodeService`  [INFERRED]
  docs/OPENAI_API.md → Android/src/app/src/main/java/com/google/ai/edge/gallery/server/PocketNodeService.kt
- `Pocket Node — OpenAI-compatible REST API` --references--> `ParsedChatRequest`  [INFERRED]
  docs/OPENAI_API.md → Android/src/app/src/main/java/com/google/ai/edge/gallery/server/openai/ChatRequestParser.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Custom Function Calling Extension Points** — function_calling_guide_actiontype, function_calling_guide_mobileactionstools, function_calling_guide_performaction, function_calling_guide_getsystemprompt [EXTRACTED 1.00]
- **JS skills implementing ai_edge_gallery_get_result contract** — android_src_app_src_main_assets_skills_interactive_map_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_mood_tracker_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_qr_code_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_query_wikipedia_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_text_spinner_scripts_index_ai_edge_gallery_get_result, ai_edge_gallery_get_result_contract [EXTRACTED 1.00]
- **Mood Music Two-Step Generation Flow** — skills_featured_mood_music_skill_two_step_genre_generation, skills_featured_mood_music_scripts_get_genres_ai_edge_gallery_get_result, skills_featured_mood_music_scripts_index_ai_edge_gallery_get_result, skills_featured_mood_music_readme_loudly_api, skills_featured_mood_music_assets_webview_audio_player [EXTRACTED 1.00]
- **Mood tracker localStorage read/write flow** — android_src_app_src_main_assets_skills_mood_tracker_scripts_index_getdata, android_src_app_src_main_assets_skills_mood_tracker_scripts_index_savedata, android_src_app_src_main_assets_skills_mood_tracker_assets_dashboard_loaddata, android_src_app_src_main_assets_skills_mood_tracker_assets_dashboard_deleteentry, android_src_app_src_main_assets_skills_mood_tracker_mood_tracker_data_storage [EXTRACTED 1.00]
- **Skill scripts implementing ai_edge_gallery_get_result** — skills_built_in_interactive_map_scripts_index_ai_edge_gallery_get_result, skills_built_in_mood_tracker_scripts_index_ai_edge_gallery_get_result, skills_built_in_qr_code_scripts_index_ai_edge_gallery_get_result, skills_built_in_query_wikipedia_scripts_index_ai_edge_gallery_get_result, skills_built_in_text_spinner_scripts_index_ai_edge_gallery_get_result [EXTRACTED 1.00]
- **Skills returning a webview response** — skills_built_in_interactive_map_scripts_index_ai_edge_gallery_get_result, skills_built_in_mood_tracker_scripts_index_ai_edge_gallery_get_result, skills_built_in_text_spinner_scripts_index_ai_edge_gallery_get_result [EXTRACTED 1.00]
- **Skills that instruct the LLM to call run_js** — android_src_app_src_main_assets_skills_calculate_hash_skill, android_src_app_src_main_assets_skills_interactive_map_skill, android_src_app_src_main_assets_skills_mood_tracker_skill, android_src_app_src_main_assets_skills_qr_code_skill, android_src_app_src_main_assets_skills_query_wikipedia_skill, android_src_app_src_main_assets_skills_text_spinner_skill, run_js_tool [EXTRACTED 1.00]
- **Skill Authoring and Publishing Pipeline** — skills_readme_skill_md, _github_workflows_static_deploy_pages, _github_discussion_template_skills_skill_webhost_path, _github_discussion_template_skills_skill_share_form [INFERRED 0.75]
- **Featured Skills Invoked via run_js Returning Webview UIs** — run_js_tool, skills_featured_mood_music_scripts_index_ai_edge_gallery_get_result, skills_featured_restaurant_roulette_scripts_index_entry, skills_featured_virtual_piano_scripts_index_entry, skills_featured_mood_music_assets_webview_audio_player, skills_featured_restaurant_roulette_assets_webview_preview_card, skills_featured_virtual_piano_assets_ui_key_generator [INFERRED 0.85]
- **Pocket Node HTTP Inference Request Flow** — android_src_app_src_main_java_com_google_ai_edge_gallery_server_pocketnodeserver, model_allowlists_readme_resolveandinitmodel, model_allowlists_readme_pocketnodemodelresolver, model_allowlists_readme_per_model_mutex, model_allowlists_readme_audiodecoderhelper, model_allowlists_readme_sanitizespokentext [INFERRED 0.85]
- **Restaurant Roulette Preview-to-Wheel Flow** — skills_featured_restaurant_roulette_assets_webview_preview_card, skills_featured_restaurant_roulette_assets_webview_redirect, skills_featured_restaurant_roulette_assets_ui_base64_place_data, skills_featured_restaurant_roulette_assets_ui_triggerspin, skills_featured_restaurant_roulette_assets_ui_animate [INFERRED 0.95]
- **Mood tracker localStorage persistence** — skills_built_in_mood_tracker_scripts_index_getdata, skills_built_in_mood_tracker_scripts_index_savedata, skills_built_in_mood_tracker_assets_dashboard_loaddata, skills_built_in_mood_tracker_assets_dashboard_deleteentry, skills_built_in_mood_tracker_scripts_index_mood_tracker_data [INFERRED 0.95]

## Communities (193 total, 68 thin omitted)

### Community 0 - "main-K5DSW5YL.js"
Cohesion: 0.03
Nodes (40): addHiddenSeeds(), addPlant(), animate(), assertInAngularZone(), assertNotInAngularZone(), av(), Bc(), cg() (+32 more)

### Community 1 - ".pipe"
Cohesion: 0.20
Nodes (23): applyRedirectCommands(), B(), expandSegmentAgainstRouteUsingRedirect(), getChildConfig(), Hn(), jn(), Ln(), match() (+15 more)

### Community 3 - "materialtheme"
Cohesion: 0.13
Nodes (6): MessageBodyError(), MessageBodyThinking(), MessageBodyWarning(), MessageLayoutConfig, MarkdownText(), humanReadableSize()

### Community 4 - "cs"
Cohesion: 0.15
Nodes (16): cs(), _d(), ec(), ef(), elementStart(), es(), fE(), Hd() (+8 more)

### Community 5 - "MobileActionsScreen.kt"
Cohesion: 0.07
Nodes (8): GalleryEvent, BUTTON_CLICKED, CAPABILITY_SELECT, GENERATE_ACTION, MODEL_DOWNLOAD, ChatWebViewJavascriptInterface, ConfigKeys, BuiltInTaskId

### Community 6 - "AppModule.kt"
Cohesion: 0.06
Nodes (10): BenchmarkResultsSerializer, CutoutsSerializer, DefaultDownloadRepository, DownloadRepository, AppModule, AppLifecycleProvider, GalleryLifecycleProvider, SettingsSerializer (+2 more)

### Community 7 - "Config"
Cohesion: 0.06
Nodes (48): isPixel10(), BooleanSwitchConfig, BottomSheetSelectorConfig, BottomSheetSelectorItem, Config, ConfigEditorType, BOOLEAN_SWITCH, BOTTOMSHEET_SELECTOR (+40 more)

### Community 8 - "MessageInputText.kt"
Cohesion: 0.06
Nodes (5): LiveCameraView(), startCamera(), isNewerRelease(), NewReleaseNotification(), ReleaseInfo

### Community 9 - "e"
Cohesion: 0.03
Nodes (4): e, reset(), toArray(), xn()

### Community 10 - "get"
Cohesion: 0.19
Nodes (13): createComment(), get(), Hr(), lc(), ngAfterViewInit(), qe(), resolveInjectorInitializers(), Rm() (+5 more)

### Community 12 - "HomeScreen.kt"
Cohesion: 0.05
Nodes (4): setScrolling(), ZoomableImage(), RotationalLoader(), HomeScreenDestination

### Community 13 - "ModelManagerViewModel"
Cohesion: 0.06
Nodes (7): ModelDownloadStatus, ModelAllowlist, DeleteModelButton(), getAllowlistUrl(), ModelInitializationStatus, ModelManagerUiState, ModelManagerViewModel

### Community 14 - "xd"
Cohesion: 0.09
Nodes (26): dc(), De(), emit(), fh(), fi(), gE(), handleError(), Ia() (+18 more)

### Community 15 - "ChatMessage.kt"
Cohesion: 0.07
Nodes (21): ChatMessageAudioClip, ChatMessageBenchmarkResult, ChatMessageClassification, ChatMessageCollapsableProgressPanel, ChatMessageImage, ChatMessageImageWithHistory, ChatMessageWebView, ChatSide (+13 more)

### Community 16 - "j"
Cohesion: 0.16
Nodes (19): cm(), dm(), ei(), fa(), fm(), gm(), Go(), ha() (+11 more)

### Community 17 - "constructor"
Cohesion: 0.05
Nodes (51): addObject(), Al(), appendChild(), ba(), Cc(), clear(), constructor(), createElement() (+43 more)

### Community 18 - "Model"
Cohesion: 0.09
Nodes (15): processLlmResponse(), Model, ChatMessage, ChatMessageBenchmarkLlmResult, ChatMessageConfigValuesChange, ChatMessageError, ChatMessageLoading, ChatMessageText (+7 more)

### Community 20 - "Text"
Cohesion: 0.09
Nodes (13): Text, BenchmarkValueSeriesViewer(), StatCell(), LogItem(), LogsViewer(), MessageBodyCollapsableProgressPanel(), ModelInitializationStatusChip(), ModelNotDownloaded() (+5 more)

### Community 21 - "GalleryNavGraph.kt"
Cohesion: 0.13
Nodes (6): enterTween(), exitTween(), slideDownExit(), slideEnter(), slideExit(), slideUpEnter()

### Community 22 - "LlmSingleTurnViewModel"
Cohesion: 0.10
Nodes (11): MessageBodyLoading(), LlmSingleTurnScreen(), LlmSingleTurnUiState, LlmSingleTurnViewModel, PromptTemplateType, CODE_SNIPPET, FREE_FORM, REWRITE_TONE (+3 more)

### Community 25 - "SkillManagerViewModel"
Cohesion: 0.12
Nodes (4): getDisplayName(), SkillManagerUiState, SkillManagerViewModel, SkillState

### Community 27 - "ChatPanel"
Cohesion: 0.10
Nodes (8): ChatMessagePromptTemplates, ChatPanel(), NestedScrollConnection, scrollToBottom(), MessageActionButton(), MessageBodyClassification(), getMessageLayoutConfig(), MessageSender()

### Community 28 - "PromptTemplateConfigs.kt"
Cohesion: 0.15
Nodes (12): PromptTemplateConfig, PromptTemplateInputEditor, PromptTemplateInputEditorType, SINGLE_SELECT, PromptTemplateSingleSelectInputEditor, SummarizationType, CONCISE_SUMMARY, HEADLINE_TITLE (+4 more)

### Community 29 - "ModelManagerViewModel.kt"
Cohesion: 0.07
Nodes (11): ProjectConfig, TokenRequestResult, TokenRequestResultType, FAILED, SUCCEEDED, USER_CANCELLED, TokenStatus, EXPIRED (+3 more)

### Community 30 - "forEach"
Cohesion: 0.05
Nodes (55): activate(), activateChildRoutes(), ag(), applyRedirectCreateUrlTree(), Bl(), bp(), bs(), createQueryParams() (+47 more)

### Community 31 - "MainUi"
Cohesion: 0.25
Nodes (3): MainUi(), MobileActionsScreen(), VoiceRecognizerOverlay()

### Community 32 - "AgentTools.kt"
Cohesion: 0.09
Nodes (22): AgentAction, AgentActionName, ASK_INFO, CALL_JS_SKILL, SKILL_PROGRESS, AskInfoAgentAction, AudioClip, CallJsAgentAction (+14 more)

### Community 33 - "Actions.kt"
Cohesion: 0.11
Nodes (11): Action, CreateCalendarEventAction, CreateContactAction, FlashlightOffAction, FlashlightOnAction, FunctionCallDetails, OpenWifiSettingsAction, SendEmailAction (+3 more)

### Community 35 - "YS"
Cohesion: 0.08
Nodes (41): Af(), bh(), co(), Dt(), Fp(), gs(), handleClickHelp(), handleCloseContentPanel() (+33 more)

### Community 37 - "DownloadAndTryButton.kt"
Cohesion: 0.07
Nodes (11): ModelDownloadStatusType, FAILED, IN_PROGRESS, NOT_DOWNLOADED, PARTIALLY_DOWNLOADED, SUCCEEDED, UNZIPPING, RuntimeType (+3 more)

### Community 38 - "HoldToDictateViewModel"
Cohesion: 0.12
Nodes (3): convertRmsDbToAmplitude(), HoldToDictateUiState, HoldToDictateViewModel

### Community 39 - "gallery/common/Utils.kt"
Cohesion: 0.09
Nodes (12): calculateInSampleSize(), calculatePeakAmplitude(), clearFocusOnKeyboardDismiss(), convert8BitTo16Bit(), convertWavToMonoWithMaxSeconds(), decodeSampledBitmapFromUri(), readFileToByteBuffer(), resample() (+4 more)

### Community 40 - "AgentChatScreen"
Cohesion: 0.13
Nodes (13): AgentChatScreen(), ChatWebViewClient, resetSessionWithCurrentSkills(), updateProgressPanel(), ChatMessageInfo, ChatView(), SendMessageTrigger, MessageBodyInfo() (+5 more)

### Community 41 - "TinyGardenTools.kt"
Cohesion: 0.20
Nodes (9): TinyGardenCommand, TinyGardenItem, DAISY, ROSE, SCYTHE, SPECIAL, SUNFLOWER, WATERING_CAN (+1 more)

### Community 42 - "PocketNodeServer.kt"
Cohesion: 0.05
Nodes (10): ApiKeyAuth, AudioDecoderHelper (MediaExtractor+MediaCodec to 16kHz mono PCM WAV), PocketNodeState.lastStartupError / isServerRunning observable state, sanitizeSpokenText (strict verbatim STT output), POST /v1/audio/transcriptions, POST /v1/chat/completions, Embedded Ktor HTTP Server (port 8080), PocketNodeService (+2 more)

### Community 43 - "has"
Cohesion: 0.12
Nodes (18): $a(), add(), _addParent(), Cf(), enqueue(), genBorderTiles(), genTiles(), getAll() (+10 more)

### Community 44 - "GalleryWebView.kt"
Cohesion: 0.14
Nodes (4): WebViewClient, BaseGalleryWebViewClient, GalleryWebView(), WebChromeClient

### Community 45 - "vy"
Cohesion: 0.08
Nodes (37): At(), _c(), cleanup(), cp(), el(), Gt(), Ho(), Ka() (+29 more)

### Community 46 - "SkillManagerViewModel.kt"
Cohesion: 0.12
Nodes (3): decodeBase64ToBitmap(), AllowedSkill, SkillAllowlist

### Community 51 - "log"
Cohesion: 0.06
Nodes (14): cleanUpMediapipeTaskErrorMessage(), Accelerator, CPU, GPU, NPU, ChatCompletionEngine, ChatEvent, ClientSideTool (+6 more)

### Community 52 - "ui/common/Utils.kt"
Cohesion: 0.17
Nodes (7): checkNotificationPermissionAndStartDownload(), createTempPictureUri(), formatToHourMinSecond(), getDistinctiveColor(), RevealingText(), SwipingText(), AppTitle()

### Community 54 - "TinyGardenViewModel.kt"
Cohesion: 0.22
Nodes (4): getTinyGardenSystemPrompt(), TinyGardenUiState, TinyGardenViewModel, ChatMessageWarning

### Community 55 - "GlobalModelManager"
Cohesion: 0.20
Nodes (3): getFileName(), GlobalModelManager(), PromoBannerGm4()

### Community 56 - "run_js tool"
Cohesion: 0.09
Nodes (23): interactive-map index.html, interactive-map Skill, qr-code index.html, qr-code Skill, query-wikipedia index.html, query-wikipedia Skill, send-email Skill, text-spinner index.html (+15 more)

### Community 57 - "vi"
Cohesion: 0.10
Nodes (24): aa(), ac(), bv(), df(), ff(), Hf(), If(), il() (+16 more)

### Community 58 - "MessageInputText"
Cohesion: 0.33
Nodes (4): MediaPanelCloseButton(), MessageInputText(), OnImageCapturedCallback, resizeBitmap()

### Community 59 - "SkillManagerBottomSheet"
Cohesion: 0.11
Nodes (12): AddSkillDisclaimerDialog(), AddSkillFromFeatureListBottomSheet(), FeaturedSkillItem(), AddSkillFromLocalImportDialog(), AddSkillFromUrlDialog(), isHostApproved(), SecretEditorDialog(), AddSkillOptionsBottomSheet() (+4 more)

### Community 61 - "Active Allowlist 1_0_11.json (.litertlm only)"
Cohesion: 0.19
Nodes (10): Build Android APK Workflow (assembleRelease, JDK 21), Google AI Edge Gallery (Android), Active Allowlist 1_0_11.json (.litertlm only), Gemma 4 / Gemma 3n / Gemma3 model family, LlmChatModelHelper Vision/Audio Encoder Text-Only Fallback, PocketNodeModelResolver (auto-discovery, req.model matching), resolveAndInitModel / executeModelInference, Google AI Edge Gallery (upstream repo) (+2 more)

### Community 62 - "GalleryTopAppBar"
Cohesion: 0.14
Nodes (9): AppBarAction, AppBarActionType, APP_SETTING, DOWNLOAD_MANAGER, MENU, NAVIGATE_UP, NO_ACTION, GalleryTopAppBar() (+1 more)

### Community 63 - "PocketNodeServer"
Cohesion: 0.10
Nodes (8): length(), BodyLimit, ParsedChatRequest, ParsedToolCall, OpenAiException, ThinkStreamSplitter, PocketNodeServer, `POST /v1/chat/completions`

### Community 64 - "ChatMessageType"
Cohesion: 0.12
Nodes (17): ChatMessageType, AUDIO_CLIP, BENCHMARK_LLM_RESULT, BENCHMARK_RESULT, CLASSIFICATION, COLLAPSABLE_PROGRESS_PANEL, CONFIG_VALUES_CHANGE, ERROR (+9 more)

### Community 65 - "mood-tracker ai_edge_gallery_get_result"
Cohesion: 0.17
Nodes (15): Mood Tracker Dashboard (dashboard.html), deleteEntry(), getISODate(), loadData(), renderTrendChart(), mood_tracker_data localStorage key, mood-tracker index.html, mood-tracker ai_edge_gallery_get_result() (+7 more)

### Community 67 - "consumeOptional"
Cohesion: 0.28
Nodes (16): capture(), consumeOptional(), jl(), parse(), parseChildren(), parseFragment(), parseMatrixParams(), parseParam() (+8 more)

### Community 72 - "Context"
Cohesion: 0.23
Nodes (3): LlmAskAudioTask, LlmAskImageTask, LlmChatTask

### Community 73 - "ExampleCustomTask.kt"
Cohesion: 0.10
Nodes (10): ExampleCustomTask, ExampleCustomTaskModelInstance, ExampleCustomTaskScreen(), Category, CategoryInfo, ModelInitializationStatusType, ERROR, INITIALIZED (+2 more)

### Community 76 - "AudioPlaybackPanel"
Cohesion: 0.23
Nodes (6): AmplitudeBarGraph(), AudioPlaybackPanel(), generateAmplitudeLevels(), playAudio(), stopPlayAudio(), MessageBodyAudioClip()

### Community 77 - "Task"
Cohesion: 0.13
Nodes (7): isLegacyTasks(), Task, ModelNameAndStatus(), StatusIcon(), ModelPageAppBar(), ModelPicker(), ModelPickerChip()

### Community 78 - "AI Edge Gallery Agent Skills"
Cohesion: 0.21
Nodes (13): Skill Share Discussion Template, Skill Webhost Path (live web deployment URL), Deploy Skills to GitHub Pages Workflow, ActionType enum / Action class (Actions.kt), Custom Function Calling Guide, MobileActionsTask.getSystemPrompt, MobileActionsTools (@Tool/@ToolParam Toolset), MobileActionsViewModel.performAction (+5 more)

### Community 80 - ".addEventListener"
Cohesion: 0.22
Nodes (3): getGlobalEventTarget(), onAndCancel(), runGuarded()

### Community 81 - "ScriptsTabContent"
Cohesion: 0.21
Nodes (4): AddOrEditSkillBottomSheet(), ScriptsTabContent(), CursorTrackingTextField(), GenerateLlmPromptBottomSheet()

### Community 83 - "DownloadAndTryButton"
Cohesion: 0.12
Nodes (6): ModelDownloadingAnimation(), ModelDownloadStatusInfoPanel(), DownloadAndTryButton(), isMemoryLow(), MemoryWarningAlert(), DownloadModelPanel()

### Community 86 - "SkillTesterBottomSheet"
Cohesion: 0.18
Nodes (5): SkillTesterBottomSheet(), PromptTemplate, SampleActionItem, Tab, MessageBodyWebview()

### Community 88 - "HomeScreen"
Cohesion: 0.18
Nodes (10): rememberDelayedAnimationProgress(), AppTitleGm4(), CategoryTabHeader(), getCategoryLabel(), HomeScreen(), IntroText(), TaskCard(), TaskList() (+2 more)

### Community 89 - "OpenAiModels.kt"
Cohesion: 0.20
Nodes (19): AssistantMessage, ChatChoice, ChatCompletionChunk, ChatCompletionRequest, ChatCompletionResponse, ChatMessageDto, ChunkChoice, ChunkDelta (+11 more)

### Community 90 - ".subscribe"
Cohesion: 0.15
Nodes (21): ad(), An(), ca(), complete(), connect(), ed(), Ee(), error() (+13 more)

### Community 91 - "ResponseUtilsTest"
Cohesion: 0.13
Nodes (3): StopSequenceFilter, ToolCallTextParser, ResponseUtilsTest

### Community 92 - "mood-tracker ai_edge_gallery_get_result (action dispatcher)"
Cohesion: 0.23
Nodes (10): Mood Tracker dashboard.html, dashboard loadData(), renderTrendChart() (Chart.js), mood-tracker ai_edge_gallery_get_result (action dispatcher), getData(), localStorage key mood_tracker_data, resolveDate(), saveData() (+2 more)

### Community 93 - "restaurant-roulette Skill Definition"
Cohesion: 0.20
Nodes (11): require-secret API Key Metadata, animate() spin with friction and winner selection, Base64 Pipe-Delimited Places Query Param (c, l, data), draw() wheel canvas, triggerSpin(), Restaurant Roulette Preview Card, redirect() to ui.html, Restaurant Roulette Skill (README) (+3 more)

### Community 94 - "file"
Cohesion: 0.17
Nodes (3): ModelDataFile, PromptTemplate, PocketNodeModelResolver

### Community 95 - "startRecording"
Cohesion: 0.22
Nodes (3): AudioRecorderPanel(), startRecording(), stopRecording()

### Community 96 - "ai_edge_gallery_get_result JS Skill Contract"
Cohesion: 0.18
Nodes (10): calculate-hash Skill, interactive-map ai_edge_gallery_get_result(), qr-code ai_edge_gallery_get_result(), query-wikipedia ai_edge_gallery_get_result(), fetchWikiFuzzyAndInfobox(), Full-Screen 3D Head Tracker (webview.html), text-spinner ai_edge_gallery_get_result(), qrcodejs 1.0.0 (CDN) (+2 more)

### Community 100 - "mood-music Skill Definition"
Cohesion: 0.29
Nodes (8): Mood Music Webview Audio Player, Loudly API, Mood Music Skill (README), get_genres ai_edge_gallery_get_result(), mood-music index ai_edge_gallery_get_result(), Mood Music Local Test Handler, Mood Music Generation Payload (genre, genre_blend, duration, energy, bpm), mood-music Skill Definition

### Community 105 - "Inject"
Cohesion: 0.13
Nodes (3): ExampleCustomTaskUiState, ExampleCustomTaskViewModel, GalleryApplication

### Community 107 - "ChatRequestParser.kt"
Cohesion: 0.16
Nodes (15): Auto, ChatRequestParser, Content, EncodedAudio, JsonObject, JsonSchema, Named, None (+7 more)

### Community 108 - "AddSkillOptionType"
Cohesion: 0.25
Nodes (6): AddSkillOption, AddSkillOptionType, FeaturedList, LocalImport, ManualInput, RemoteUrl

### Community 109 - "ActionType"
Cohesion: 0.25
Nodes (8): ActionType, ACTION_CREATE_CALENDAR_EVENT, ACTION_CREATE_CONTACT, ACTION_FLASHLIGHT_OFF, ACTION_FLASHLIGHT_ON, ACTION_OPEN_WIFI_SETTINGS, ACTION_SEND_EMAIL, ACTION_SHOW_LOCATION_ON_MAP

### Community 112 - "ConfigValue"
Cohesion: 0.36
Nodes (7): ConfigValue, FloatValue, getFloatConfigValue(), getIntConfigValue(), getStringConfigValue(), IntValue, StringValue

### Community 115 - ".setupNavigations"
Cohesion: 0.16
Nodes (21): Ae(), ai(), bm(), ci(), Fn(), gg(), Gu(), H() (+13 more)

### Community 117 - "88-Key Piano Keyboard Generator"
Cohesion: 0.33
Nodes (7): Piano Audio Engine (per-note Audio, pointer handlers), 88-Key Piano Keyboard Generator, Auto-scroll to Middle C, fuhton/piano-mp3 (MIT piano samples), Virtual Piano Skill (README), Virtual Piano Script Entry (loads index.js), virtual-piano Skill Definition

### Community 118 - "getJsonResponse"
Cohesion: 0.33
Nodes (3): JsonObjAndTextContent, getJsonResponse(), parseJson()

### Community 119 - "w"
Cohesion: 0.14
Nodes (23): Ar(), bf(), bg(), create(), da(), G(), ki(), lineralizeSegments() (+15 more)

### Community 120 - "LlmAskAudioModule"
Cohesion: 0.60
Nodes (3): LlmAskAudioModule, LlmAskImageModule, LlmChatTaskModule

### Community 127 - "ParsedTurn"
Cohesion: 0.18
Nodes (4): ParsedTurn, LiteRtLmMapper, FunctionDefinition, LiteRtLmMapperTest

### Community 129 - "MessageBubbleShape.kt"
Cohesion: 0.14
Nodes (4): MessageBubbleShape, GlitteringShapesLoader(), Particle(), Shape

### Community 130 - "wp"
Cohesion: 0.50
Nodes (3): clone(), createEmbeddedView(), wp()

### Community 131 - "Android Full Bug Report Capture Guide"
Cohesion: 0.67
Nodes (3): Bug Report Issue Template, adb bugreport, Android Full Bug Report Capture Guide

### Community 132 - "init"
Cohesion: 1.00
Nodes (3): init(), predict(), MediaPipe FaceLandmarker (tasks-vision)

### Community 134 - "HuggingFace OAuth Developer Application"
Cohesion: 0.67
Nodes (3): appAuthRedirectScheme manifest placeholder, HuggingFace OAuth Developer Application, ProjectConfig.kt (clientId, redirectUri)

### Community 136 - "Theme.kt"
Cohesion: 0.14
Nodes (4): CustomColors, GalleryTheme(), StatusBarColorController(), ThemeSettings

### Community 137 - "toString"
Cohesion: 0.29
Nodes (5): keys(), lg(), _s(), tg(), toString()

### Community 139 - "getTaskBgGradientColors"
Cohesion: 0.17
Nodes (7): MessageBodyPromptTemplates(), getTaskBgColor(), getTaskBgGradientColors(), getTaskIconColor(), getTaskIconBgShape(), TaskIcon(), ModelList()

### Community 140 - "yy"
Cohesion: 0.12
Nodes (16): addClass(), attachToAppRef(), bi(), gf(), gv(), insertView(), jp(), qv() (+8 more)

### Community 150 - "Bo"
Cohesion: 0.14
Nodes (15): Bo(), Fo(), Gy(), Ih(), k(), kp(), lo(), Mc() (+7 more)

### Community 151 - "MainUi"
Cohesion: 0.17
Nodes (4): ConversationHistoryPanel(), MainUi(), WebChromeClient, TinyGardenScreen()

### Community 152 - "t"
Cohesion: 0.21
Nodes (14): children(), clearTimeout(), t(), firstChild(), Jc(), jm(), mf(), ou() (+6 more)

### Community 153 - "r"
Cohesion: 0.20
Nodes (12): r(), Fc(), hs(), id(), md(), qp(), $r(), rf() (+4 more)

### Community 154 - "BenchmarkResultsViewer"
Cohesion: 0.20
Nodes (6): BenchmarkResultsViewer(), getAggregationValue(), getBenchmarkResultCsv(), StatRow(), ValueSeriesRow(), Accordions()

### Community 155 - "destroy"
Cohesion: 0.23
Nodes (12): ay(), destroy(), detach(), Fr(), ft(), Gi(), Mr(), ngOnDestroy() (+4 more)

### Community 156 - "embeddedTView"
Cohesion: 0.18
Nodes (12): addMatch(), detachView(), dirtyQueriesWithMatches(), embeddedTView(), finishViewCreation(), getByIndex(), isApplyingToNode(), Li() (+4 more)

### Community 157 - "o"
Cohesion: 0.20
Nodes (12): as(), by(), cv(), jf(), lv(), preloadSounds(), sc(), sy() (+4 more)

### Community 158 - "Wo"
Cohesion: 0.18
Nodes (12): Bu(), Hu(), km(), next(), Oo(), retrieve(), shouldAttach(), shouldReuseRoute() (+4 more)

### Community 159 - "n"
Cohesion: 0.21
Nodes (12): cE(), dd(), dy(), fy(), n(), pE(), pi(), sa() (+4 more)

### Community 162 - "insertImpl"
Cohesion: 0.20
Nodes (9): _adjustIndex(), attachToViewContainerRef(), createComponent(), insert(), insertImpl(), move(), resolveComponentFactory(), Rt() (+1 more)

### Community 163 - "L"
Cohesion: 0.22
Nodes (9): bd(), eh(), fs(), L(), LS(), PS(), ro(), us() (+1 more)

### Community 164 - "Mn"
Cohesion: 0.31
Nodes (9): applyValueToInputSignal(), ea(), ku(), mm(), Mn(), oa(), om(), Pu() (+1 more)

### Community 165 - "find"
Cohesion: 0.22
Nodes (9): dispose(), eg(), find(), getSelectedSlotObjectType(), Is(), isSlotLocked(), removeBubble(), removeObject() (+1 more)

### Community 166 - "DataCard"
Cohesion: 0.25
Nodes (4): DataCard(), DataCardPreview(), MessageBodyBenchmark(), MessageBodyBenchmarkLlm()

### Community 167 - "GalleryNavHost"
Cohesion: 0.25
Nodes (4): ErrorDialog(), PromoScreenGm4(), CustomTaskScreen(), GalleryNavHost()

### Community 168 - "Pocket Node — OpenAI-compatible REST API"
Cohesion: 0.22
Nodes (9): Authentication and security, Endpoints, Errors, Examples, `GET /v1/models` and `GET /v1/models/{id}`, Limitations, Pocket Node — OpenAI-compatible REST API, `POST /v1/audio/transcriptions` (+1 more)

### Community 169 - "run"
Cohesion: 0.29
Nodes (8): ap(), flush(), flushQueue(), ny(), run(), ry(), Yc(), Yn()

### Community 170 - "bn"
Cohesion: 0.36
Nodes (8): Au(), bn(), consumerOnSignalRead(), em(), Ru(), ta(), Tn(), zt()

### Community 171 - "ct"
Cohesion: 0.25
Nodes (8): be(), cd(), ct(), hi(), hydrate(), ld(), track(), zm()

### Community 172 - "Gc"
Cohesion: 0.29
Nodes (6): cn(), destroyed(), Gc(), ly(), uy(), xa()

### Community 173 - "kd"
Cohesion: 0.36
Nodes (5): dh(), kd(), lh(), uo(), Zr()

### Community 174 - "LanguageType"
Cohesion: 0.25
Nodes (8): LanguageType, CPP, JAVA, JAVASCRIPT, KOTLIN, PYTHON, SWIFT, TYPESCRIPT

### Community 175 - "I"
Cohesion: 0.29
Nodes (7): decoratePreventDefault(), hE(), hy(), I(), Ke(), listen(), Nr()

### Community 176 - "Wf"
Cohesion: 0.29
Nodes (7): detachFromAppRef(), Ep(), $f(), hc(), iy(), parentInjector(), Wf()

### Community 177 - "RewriteToneType"
Cohesion: 0.29
Nodes (7): RewriteToneType, CASUAL, CONCISE, ENTHUSIASTIC, FORMAL, FRIENDLY, POLITE

### Community 178 - "Aggregation"
Cohesion: 0.33
Nodes (5): Aggregation, AVG, MAX, MEDIAN, MIN

### Community 180 - "indexOf"
Cohesion: 0.40
Nodes (5): Fd(), indexOf(), ml(), onDestroy(), removeOnDestroy()

### Community 183 - "di"
Cohesion: 0.67
Nodes (4): di(), ga(), Qt(), yi()

### Community 184 - "rd"
Cohesion: 0.50
Nodes (4): gh(), oh(), rd(), ym()

### Community 185 - "iu"
Cohesion: 0.50
Nodes (4): iu(), parent(), pathFromRoot(), siblings()

### Community 187 - "InputEditorLabel"
Cohesion: 0.50
Nodes (4): InputEditorLabel, LANGUAGE, STYLE, TONE

### Community 188 - "createSegments"
Cohesion: 0.67
Nodes (3): createSegments(), findOrReturn(), findPosParam()

## Ambiguous Edges - Review These
- `skills/calculate-hash/scripts/index.js` → `ai_edge_gallery_get_result JS Skill Contract`  [AMBIGUOUS]
  Android/src/app/src/main/assets/skills/calculate-hash/scripts/index.html · relation: implements
- `built-in/calculate-hash/scripts/index.js` → `ai_edge_gallery_get_result JS skill entrypoint protocol`  [AMBIGUOUS]
  skills/built-in/calculate-hash/scripts/index.html · relation: implements
- `Mood Tracker Dashboard (dashboard.html)` → `Tiny Garden (Angular app shell)`  [AMBIGUOUS]
  Android/src/app/src/main/assets/tinygarden/index.html · relation: conceptually_related_to

## Knowledge Gaps
- **168 isolated node(s):** `CAPABILITY_SELECT`, `MODEL_DOWNLOAD`, `GENERATE_ACTION`, `BUTTON_CLICKED`, `LatencyProvider` (+163 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 741 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **68 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `skills/calculate-hash/scripts/index.js` and `ai_edge_gallery_get_result JS Skill Contract`?**
  _Edge tagged AMBIGUOUS (relation: implements) - confidence is low._
- **Why does ``POST /v1/chat/completions`` connect `PocketNodeServer` to `Pocket Node — OpenAI-compatible REST API`, `log`, `AudioDecoderHelper.kt`, `n`?**
  _High betweenness centrality (0.397) - this node is a cross-community bridge._
- **Are the 100 inferred relationships involving `Text` (e.g. with `AddOrEditSkillBottomSheet()` and `ScriptsTabContent()`) actually correct?**
  _`Text` has 100 INFERRED edges - model-reasoned connections that need verification._
- **What connects `CAPABILITY_SELECT`, `MODEL_DOWNLOAD`, `GENERATE_ACTION` to the rest of the system?**
  _168 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `main-K5DSW5YL.js` be split into smaller, more focused modules?**
  _Cohesion score 0.025322503583373148 - nodes in this community are weakly interconnected._
- **What is the exact relationship between `built-in/calculate-hash/scripts/index.js` and `ai_edge_gallery_get_result JS skill entrypoint protocol`?**
  _Edge tagged AMBIGUOUS (relation: implements) - confidence is low._
- **Why does `LlmModelInstance` connect `log` to `MobileActionsViewModel.kt`, `TinyGardenViewModel.kt`, `PocketNodeServer`?**
  _High betweenness centrality (0.276) - this node is a cross-community bridge._