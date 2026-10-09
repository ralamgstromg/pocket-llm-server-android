# Graph Report - pocket-llm-server-android  (2026-10-09)

## Corpus Check
- 385 files · ~160,038 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 57 file(s) not represented in the graph (top: .xml 30, .ttf 10, (none) 8)

## Summary
- 3386 nodes · 10854 edges · 150 communities (85 shown, 65 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 392 edges (avg confidence: 0.85)
- Token cost: 222,803 input · 0 output

## Community Hubs (Navigation)
- TinyGarden Angular Bundle 1
- TinyGarden Angular Bundle 2
- Skill Manager Dialogs UI
- Compose Shared UI Widgets
- TinyGarden Angular Bundle 3
- Analytics And Chat WebView
- DataStore Serializers And DI
- Model Config Editors
- Camera And Audio Input UI
- TinyGarden Angular Bundle 4
- TinyGarden Angular Bundle 5
- Benchmark Results Viewer
- Animations And Custom Shapes
- Model Manager ViewModel
- TinyGarden Angular Bundle 6
- Chat Message Types
- TinyGarden Angular Bundle 7
- TinyGarden Angular Bundle 8
- Model Definition And Runtime
- Home Screen And ToS Dialogs
- Navigation And Custom Tasks
- LLM Single-Turn ViewModels
- Links And Release Notification
- MainActivity And Theme
- Skill Manager ViewModel
- Message Body Rendering
- Chat Panel Scrolling
- Prompt Template Configs
- HuggingFace Auth Tokens
- TinyGarden Angular Bundle 9
- Task Registry And Main UI
- Agent Actions And JS Skills
- Mobile Actions Definitions
- Model Download Worker
- TinyGarden Angular Bundle 10
- Loading And Empty States
- Model Download Status UI
- Hold-To-Dictate Speech
- Audio And Bitmap Utils
- Agent Chat Screen WebView
- TinyGarden Tools
- Pocket Node HTTP Server
- TinyGarden Angular Bundle 11
- Gallery WebView Client
- TinyGarden Angular Bundle 12
- Skill Allowlist Loading
- DataStore Repository Interface
- DataStore Repository Impl
- Accordion Result Views
- FCM Messaging And Constants
- LLM Chat Model Helper
- UI Common Utils
- Mobile Actions ViewModel
- TinyGarden ViewModel
- Task Icons And Global Models
- Skill Scripts And Email
- TinyGarden Angular Bundle 13
- Message Input Handling
- Featured Skills Bottom Sheet
- Mobile Actions Task
- Repo Docs And Allowlist
- App Bar Actions
- LLM Chat ViewModel
- Chat Message Type Enum
- Mood Tracker Dashboard (assets)
- TinyGarden Angular Bundle 14
- TinyGarden Angular Bundle 15
- Agent Chat Task Module
- Server Audio Decoder
- LLM Single-Turn Module
- LLM Model Helper Interface
- LLM Chat Task Modules
- Example Custom Task
- Mobile Actions VM Wiring
- TinyGarden Task
- Audio Playback Panel
- Model Page App Bar
- Function Calling And Skill Publishing
- Download Repository
- TinyGarden Angular Bundle 16
- Add/Edit Skill Sheet
- Custom Task Interface
- Memory Warning And Download
- TinyGarden Angular Bundle 17
- Skill Tester Sheet
- Home Screen Layout
- LLM Chat Helper Internals
- Model Init Status
- JS Skill Entrypoint (assets)
- Mood Tracker Dashboard (built-in)
- Restaurant Roulette Skill
- Pocket Node Model Resolver
- Audio Recorder Panel
- JS Skill Entrypoint (built-in)
- TinyGarden Angular Bundle 19
- Pocket Node State And Dialog
- Sensor Observer
- Mood Music Skill
- TinyGarden Angular Bundle 20
- Intent Handler
- Gallery Application
- TinyGarden Angular Bundle 21
- Model Allowlist
- Add Skill Options
- Mobile Action Types
- Mobile Actions DI Module
- TinyGarden DI Module
- Config Values
- App ToS Dialog
- ToS ViewModel
- TinyGarden Angular Bundle 22
- Example Task DI Module
- Virtual Piano Skill
- JSON Response Utils
- Home Categories
- Ask Audio DI Module
- Gallery App Root
- Pocket Node Service
- Message Latency
- Square Drawer Item
- Pocket Node Design Notes
- TinyGarden Angular Bundle 23
- Bug Reporting
- Text Spinner Face Tracking
- HuggingFace OAuth Setup
- Kitchen Adventure (assets)
- TinyGarden Angular Bundle 25
- TinyGarden Angular Bundle 26
- TinyGarden Angular Bundle 27
- TinyGarden Angular Bundle 28
- TinyGarden Angular Bundle 29
- Kitchen Adventure (built-in)
- Feature Request Template
- Support Request Template
- Contribution Policy

## God Nodes (most connected - your core abstractions)
1. `e` - 208 edges
2. `Model` - 177 edges
3. `ModelManagerViewModel` - 139 edges
4. `Task` - 111 edges
5. `t()` - 75 edges
6. `n()` - 75 edges
7. `constructor()` - 64 edges
8. `DataStoreRepository` - 53 edges
9. `r()` - 51 edges
10. `DefaultDataStoreRepository` - 47 edges

## Surprising Connections (you probably didn't know these)
- `Custom Function Calling Guide` --semantically_similar_to--> `Native Skills (run_intent tool)`  [INFERRED] [semantically similar]
  Function_Calling_Guide.md → skills/README.md
- `Skill Share Discussion Template` --conceptually_related_to--> `AI Edge Gallery Agent Skills`  [INFERRED]
  .github/DISCUSSION_TEMPLATE/skills.yml → skills/README.md
- `Build Android APK Workflow (assembleRelease, JDK 21)` --conceptually_related_to--> `LiteRT-LM Inference Engine`  [INFERRED]
  .github/workflows/build_android.yaml → README.md
- `AI Edge Gallery Agent Skills` --conceptually_related_to--> `Google AI Edge Gallery (upstream repo)`  [INFERRED]
  skills/README.md → README.md
- `Android Full Bug Report Capture Guide` --conceptually_related_to--> `Bug Report Issue Template`  [INFERRED]
  Bug_Reporting_Guide.md → .github/ISSUE_TEMPLATE/bug_report.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Pocket Node HTTP Inference Request Flow** — android_src_app_src_main_java_com_google_ai_edge_gallery_server_pocketnodeserver, model_allowlists_readme_resolveandinitmodel, model_allowlists_readme_pocketnodemodelresolver, model_allowlists_readme_per_model_mutex, model_allowlists_readme_audiodecoderhelper, model_allowlists_readme_sanitizespokentext [INFERRED 0.85]
- **Custom Function Calling Extension Points** — function_calling_guide_actiontype, function_calling_guide_mobileactionstools, function_calling_guide_performaction, function_calling_guide_getsystemprompt [EXTRACTED 1.00]
- **Skill Authoring and Publishing Pipeline** — skills_readme_skill_md, _github_workflows_static_deploy_pages, _github_discussion_template_skills_skill_webhost_path, _github_discussion_template_skills_skill_share_form [INFERRED 0.75]
- **JS skills implementing ai_edge_gallery_get_result contract** — android_src_app_src_main_assets_skills_interactive_map_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_mood_tracker_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_qr_code_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_query_wikipedia_scripts_index_ai_edge_gallery_get_result, android_src_app_src_main_assets_skills_text_spinner_scripts_index_ai_edge_gallery_get_result, ai_edge_gallery_get_result_contract [EXTRACTED 1.00]
- **Skills that instruct the LLM to call run_js** — android_src_app_src_main_assets_skills_calculate_hash_skill, android_src_app_src_main_assets_skills_interactive_map_skill, android_src_app_src_main_assets_skills_mood_tracker_skill, android_src_app_src_main_assets_skills_qr_code_skill, android_src_app_src_main_assets_skills_query_wikipedia_skill, android_src_app_src_main_assets_skills_text_spinner_skill, run_js_tool [EXTRACTED 1.00]
- **Mood tracker localStorage read/write flow** — android_src_app_src_main_assets_skills_mood_tracker_scripts_index_getdata, android_src_app_src_main_assets_skills_mood_tracker_scripts_index_savedata, android_src_app_src_main_assets_skills_mood_tracker_assets_dashboard_loaddata, android_src_app_src_main_assets_skills_mood_tracker_assets_dashboard_deleteentry, android_src_app_src_main_assets_skills_mood_tracker_mood_tracker_data_storage [EXTRACTED 1.00]
- **Skill scripts implementing ai_edge_gallery_get_result** — skills_built_in_interactive_map_scripts_index_ai_edge_gallery_get_result, skills_built_in_mood_tracker_scripts_index_ai_edge_gallery_get_result, skills_built_in_qr_code_scripts_index_ai_edge_gallery_get_result, skills_built_in_query_wikipedia_scripts_index_ai_edge_gallery_get_result, skills_built_in_text_spinner_scripts_index_ai_edge_gallery_get_result [EXTRACTED 1.00]
- **Skills returning a webview response** — skills_built_in_interactive_map_scripts_index_ai_edge_gallery_get_result, skills_built_in_mood_tracker_scripts_index_ai_edge_gallery_get_result, skills_built_in_text_spinner_scripts_index_ai_edge_gallery_get_result [EXTRACTED 1.00]
- **Mood tracker localStorage persistence** — skills_built_in_mood_tracker_scripts_index_getdata, skills_built_in_mood_tracker_scripts_index_savedata, skills_built_in_mood_tracker_assets_dashboard_loaddata, skills_built_in_mood_tracker_assets_dashboard_deleteentry, skills_built_in_mood_tracker_scripts_index_mood_tracker_data [INFERRED 0.95]
- **Featured Skills Invoked via run_js Returning Webview UIs** — run_js_tool, skills_featured_mood_music_scripts_index_ai_edge_gallery_get_result, skills_featured_restaurant_roulette_scripts_index_entry, skills_featured_virtual_piano_scripts_index_entry, skills_featured_mood_music_assets_webview_audio_player, skills_featured_restaurant_roulette_assets_webview_preview_card, skills_featured_virtual_piano_assets_ui_key_generator [INFERRED 0.85]
- **Mood Music Two-Step Generation Flow** — skills_featured_mood_music_skill_two_step_genre_generation, skills_featured_mood_music_scripts_get_genres_ai_edge_gallery_get_result, skills_featured_mood_music_scripts_index_ai_edge_gallery_get_result, skills_featured_mood_music_readme_loudly_api, skills_featured_mood_music_assets_webview_audio_player [EXTRACTED 1.00]
- **Restaurant Roulette Preview-to-Wheel Flow** — skills_featured_restaurant_roulette_assets_webview_preview_card, skills_featured_restaurant_roulette_assets_webview_redirect, skills_featured_restaurant_roulette_assets_ui_base64_place_data, skills_featured_restaurant_roulette_assets_ui_triggerspin, skills_featured_restaurant_roulette_assets_ui_animate [INFERRED 0.95]

## Communities (150 total, 65 thin omitted)

### Community 0 - "TinyGarden Angular Bundle 1"
Cohesion: 0.02
Nodes (150): addClass(), addMatch(), addPlant(), animate(), applyValueToInputSignal(), assertInAngularZone(), assertNotInAngularZone(), attachToAppRef() (+142 more)

### Community 1 - "TinyGarden Angular Bundle 2"
Cohesion: 0.05
Nodes (96): ad(), addHiddenSeeds(), Ae(), ai(), An(), applyRedirectCommands(), Ar(), B() (+88 more)

### Community 4 - "TinyGarden Angular Bundle 3"
Cohesion: 0.05
Nodes (90): ap(), as(), bf(), by(), cE(), clearTimeout(), create(), createComponent() (+82 more)

### Community 5 - "Analytics And Chat WebView"
Cohesion: 0.05
Nodes (16): GalleryEvent, BUTTON_CLICKED, CAPABILITY_SELECT, GENERATE_ACTION, MODEL_DOWNLOAD, ChatWebViewJavascriptInterface, ConfigKeys, ModelDownloadStatusType (+8 more)

### Community 6 - "DataStore Serializers And DI"
Cohesion: 0.06
Nodes (10): BenchmarkResultsSerializer, CutoutsSerializer, DefaultDownloadRepository, DownloadRepository, AppModule, AppLifecycleProvider, GalleryLifecycleProvider, SettingsSerializer (+2 more)

### Community 7 - "Model Config Editors"
Cohesion: 0.06
Nodes (48): BooleanSwitchConfig, BottomSheetSelectorConfig, BottomSheetSelectorItem, Config, ConfigEditorType, BOOLEAN_SWITCH, BOTTOMSHEET_SELECTOR, LABEL (+40 more)

### Community 9 - "TinyGarden Angular Bundle 4"
Cohesion: 0.03
Nodes (6): e, reset(), tf(), toArray(), xn(), ym()

### Community 10 - "TinyGarden Angular Bundle 5"
Cohesion: 0.05
Nodes (53): _adjustIndex(), applyStyles(), applyToHost(), attachToViewContainerRef(), createComment(), ct(), destroy(), detach() (+45 more)

### Community 11 - "Benchmark Results Viewer"
Cohesion: 0.06
Nodes (16): BenchmarkModelPicker(), BenchmarkResultsViewer(), getAggregationValue(), getBenchmarkResultCsv(), StatRow(), ValueSeriesRow(), BenchmarkValueSeriesViewer(), StatCell() (+8 more)

### Community 12 - "Animations And Custom Shapes"
Cohesion: 0.05
Nodes (7): AudioAnimation(), MessageBubbleShape, setScrolling(), ZoomableImage(), GlitteringShapesLoader(), Particle(), Shape

### Community 14 - "TinyGarden Angular Bundle 6"
Cohesion: 0.06
Nodes (46): _c(), dc(), De(), dv(), elementEnd(), emit(), fh(), fi() (+38 more)

### Community 15 - "Chat Message Types"
Cohesion: 0.07
Nodes (25): ChatMessageAudioClip, ChatMessageBenchmarkLlmResult, ChatMessageBenchmarkResult, ChatMessageClassification, ChatMessageCollapsableProgressPanel, ChatMessageImage, ChatMessageImageWithHistory, ChatMessageThinking (+17 more)

### Community 16 - "TinyGarden Angular Bundle 7"
Cohesion: 0.06
Nodes (40): add(), _addParent(), Bu(), ca(), cm(), complete(), error(), fa() (+32 more)

### Community 17 - "TinyGarden Angular Bundle 8"
Cohesion: 0.07
Nodes (41): addObject(), ba(), clear(), constructor(), handleClickChicken(), handleClickExitTutorial(), handleTapOverlay(), harvestGarden() (+33 more)

### Community 18 - "Model Definition And Runtime"
Cohesion: 0.10
Nodes (12): Model, ModelDataFile, PromptTemplate, RuntimeType, LITERT_LM, UNKNOWN, ChatMessage, ChatMessageConfigValuesChange (+4 more)

### Community 21 - "Navigation And Custom Tasks"
Cohesion: 0.08
Nodes (15): CustomTaskData, CustomTaskDataForBuiltinTask, isLegacyTasks(), ModelDownloadingAnimation(), ModelDownloadStatusInfoPanel(), ErrorDialog(), PromoScreenGm4(), CustomTaskScreen() (+7 more)

### Community 22 - "LLM Single-Turn ViewModels"
Cohesion: 0.08
Nodes (11): processLlmResponse(), ExampleCustomTaskUiState, ExampleCustomTaskViewModel, LlmSingleTurnUiState, LlmSingleTurnViewModel, PromptTemplateType, CODE_SNIPPET, FREE_FORM (+3 more)

### Community 23 - "Links And Release Notification"
Cohesion: 0.07
Nodes (6): buildTrackableUrlAnnotatedString(), ClickableLink(), MobileActionsChallengeDialog(), isNewerRelease(), NewReleaseNotification(), ReleaseInfo

### Community 24 - "MainActivity And Theme"
Cohesion: 0.07
Nodes (4): MainActivity, CustomColors, GalleryTheme(), StatusBarColorController()

### Community 25 - "Skill Manager ViewModel"
Cohesion: 0.12
Nodes (5): AddSkillFromLocalImportDialog(), getDisplayName(), SkillManagerUiState, SkillManagerViewModel, SkillState

### Community 26 - "Message Body Rendering"
Cohesion: 0.06
Nodes (10): SecretEditorDialog(), ConversationHistoryPanel(), MessageBodyText(), MessageBodyThinking(), MessageBodyWarning(), getMessageLayoutConfig(), MessageLayoutConfig, MessageSender() (+2 more)

### Community 27 - "Chat Panel Scrolling"
Cohesion: 0.06
Nodes (13): ChatMessagePromptTemplates, ChatPanel(), NestedScrollConnection, scrollToBottom(), DataCard(), DataCardPreview(), MessageActionButton(), MessageBodyBenchmark() (+5 more)

### Community 28 - "Prompt Template Configs"
Cohesion: 0.06
Nodes (31): InputEditorLabel, LANGUAGE, STYLE, TONE, LanguageType, CPP, JAVA, JAVASCRIPT (+23 more)

### Community 29 - "HuggingFace Auth Tokens"
Cohesion: 0.07
Nodes (11): ProjectConfig, TokenRequestResult, TokenRequestResultType, FAILED, SUCCEEDED, USER_CANCELLED, TokenStatus, EXPIRED (+3 more)

### Community 30 - "TinyGarden Angular Bundle 9"
Cohesion: 0.09
Nodes (28): activate(), activateChildRoutes(), applyRedirectCreateUrlTree(), bd(), Bl(), createQueryParams(), createSegmentGroup(), deactivateChildRoutes() (+20 more)

### Community 31 - "Task Registry And Main UI"
Cohesion: 0.11
Nodes (10): MainUi(), MobileActionsScreen(), Task, getTaskBgColor(), getTaskBgGradientColors(), getTaskIconColor(), HoldToDictate(), TextAndVoiceInput() (+2 more)

### Community 32 - "Agent Actions And JS Skills"
Cohesion: 0.13
Nodes (18): AgentAction, AgentActionName, ASK_INFO, CALL_JS_SKILL, SKILL_PROGRESS, AskInfoAgentAction, CallJsAgentAction, CallJsSkillResult (+10 more)

### Community 33 - "Mobile Actions Definitions"
Cohesion: 0.11
Nodes (11): Action, CreateCalendarEventAction, CreateContactAction, FlashlightOffAction, FlashlightOnAction, FunctionCallDetails, OpenWifiSettingsAction, SendEmailAction (+3 more)

### Community 35 - "TinyGarden Angular Bundle 10"
Cohesion: 0.13
Nodes (24): Al(), Cc(), createElement(), ey(), getDefaultDocument(), Jv(), qc(), qu() (+16 more)

### Community 36 - "Loading And Empty States"
Cohesion: 0.11
Nodes (4): MessageBodyLoading(), EmptyState(), EmptyStateButtonConfig, RotationalLoader()

### Community 37 - "Model Download Status UI"
Cohesion: 0.09
Nodes (7): ModelDownloadStatus, ConfirmDeleteModelDialog(), DeleteModelButton(), DownloadModelPanel(), ModelItem(), ModelNameAndStatus(), StatusIcon()

### Community 38 - "Hold-To-Dictate Speech"
Cohesion: 0.12
Nodes (3): convertRmsDbToAmplitude(), HoldToDictateUiState, HoldToDictateViewModel

### Community 39 - "Audio And Bitmap Utils"
Cohesion: 0.11
Nodes (9): calculateInSampleSize(), calculatePeakAmplitude(), clearFocusOnKeyboardDismiss(), convert8BitTo16Bit(), convertWavToMonoWithMaxSeconds(), decodeSampledBitmapFromUri(), readFileToByteBuffer(), resample() (+1 more)

### Community 40 - "Agent Chat Screen WebView"
Cohesion: 0.13
Nodes (13): AgentChatScreen(), ChatWebViewClient, resetSessionWithCurrentSkills(), updateProgressPanel(), ChatMessageInfo, ChatView(), SendMessageTrigger, MessageBodyInfo() (+5 more)

### Community 41 - "TinyGarden Tools"
Cohesion: 0.11
Nodes (12): MainUi(), WebChromeClient, TinyGardenScreen(), TinyGardenCommand, TinyGardenItem, DAISY, ROSE, SCYTHE (+4 more)

### Community 42 - "Pocket Node HTTP Server"
Cohesion: 0.13
Nodes (8): ChatRequest, ChatResponse, Choice, Message, PocketNodeServer, TranscriptionResponse, AudioDecoderHelper (MediaExtractor+MediaCodec to 16kHz mono PCM WAV), sanitizeSpokenText (strict verbatim STT output)

### Community 43 - "TinyGarden Angular Bundle 11"
Cohesion: 0.08
Nodes (26): $a(), ag(), bs(), bv(), Cf(), Do(), hasChildren(), hl() (+18 more)

### Community 44 - "Gallery WebView Client"
Cohesion: 0.13
Nodes (4): WebViewClient, BaseGalleryWebViewClient, GalleryWebView(), WebChromeClient

### Community 45 - "TinyGarden Angular Bundle 12"
Cohesion: 0.12
Nodes (24): appendChild(), At(), el(), hp(), hy(), insertBefore(), Kc(), Ke() (+16 more)

### Community 46 - "Skill Allowlist Loading"
Cohesion: 0.10
Nodes (3): decodeBase64ToBitmap(), AllowedSkill, SkillAllowlist

### Community 52 - "UI Common Utils"
Cohesion: 0.17
Nodes (7): checkNotificationPermissionAndStartDownload(), createTempPictureUri(), formatToHourMinSecond(), getDistinctiveColor(), RevealingText(), SwipingText(), AppTitle()

### Community 54 - "TinyGarden ViewModel"
Cohesion: 0.22
Nodes (4): getTinyGardenSystemPrompt(), TinyGardenUiState, TinyGardenViewModel, ChatMessageWarning

### Community 55 - "Task Icons And Global Models"
Cohesion: 0.14
Nodes (5): getTaskIconBgShape(), TaskIcon(), getFileName(), GlobalModelManager(), PromoBannerGm4()

### Community 56 - "Skill Scripts And Email"
Cohesion: 0.11
Nodes (14): calculate-hash Skill, interactive-map index.html, interactive-map Skill, qr-code index.html, qr-code Skill, query-wikipedia index.html, query-wikipedia Skill, send-email Skill (+6 more)

### Community 57 - "TinyGarden Angular Bundle 13"
Cohesion: 0.12
Nodes (18): aa(), ac(), detachFromAppRef(), Ep(), $f(), ff(), hc(), iy() (+10 more)

### Community 58 - "Message Input Handling"
Cohesion: 0.14
Nodes (10): AudioClip, checkFrontCamera(), handleAudioWavSelected(), handleImagesSelected(), MediaPanelCloseButton(), MessageInputText(), OnImageCapturedCallback, resizeBitmap() (+2 more)

### Community 59 - "Featured Skills Bottom Sheet"
Cohesion: 0.16
Nodes (9): AddSkillDisclaimerDialog(), AddSkillFromFeatureListBottomSheet(), FeaturedSkillItem(), AddSkillFromUrlDialog(), isHostApproved(), AddSkillOptionsBottomSheet(), scrollToBottomOfList(), SkillItemRow() (+1 more)

### Community 61 - "Repo Docs And Allowlist"
Cohesion: 0.14
Nodes (14): Build Android APK Workflow (assembleRelease, JDK 21), Google AI Edge Gallery (Android), Active Allowlist 1_0_11.json (.litertlm only), Gemma 4 / Gemma 3n / Gemma3 model family, LlmChatModelHelper Vision/Audio Encoder Text-Only Fallback, PocketNodeModelResolver (auto-discovery, req.model matching), resolveAndInitModel / executeModelInference, POST /v1/audio/transcriptions (+6 more)

### Community 62 - "App Bar Actions"
Cohesion: 0.17
Nodes (9): AppBarAction, AppBarActionType, APP_SETTING, DOWNLOAD_MANAGER, MENU, NAVIGATE_UP, NO_ACTION, GalleryTopAppBar() (+1 more)

### Community 63 - "LLM Chat ViewModel"
Cohesion: 0.18
Nodes (5): ChatMessageError, MessageBodyError(), LlmAskAudioViewModel, LlmAskImageViewModel, LlmChatViewModelBase

### Community 64 - "Chat Message Type Enum"
Cohesion: 0.12
Nodes (17): ChatMessageType, AUDIO_CLIP, BENCHMARK_LLM_RESULT, BENCHMARK_RESULT, CLASSIFICATION, COLLAPSABLE_PROGRESS_PANEL, CONFIG_VALUES_CHANGE, ERROR (+9 more)

### Community 65 - "Mood Tracker Dashboard (assets)"
Cohesion: 0.17
Nodes (15): Mood Tracker Dashboard (dashboard.html), deleteEntry(), getISODate(), loadData(), renderTrendChart(), mood_tracker_data localStorage key, mood-tracker index.html, mood-tracker ai_edge_gallery_get_result() (+7 more)

### Community 66 - "TinyGarden Angular Bundle 14"
Cohesion: 0.15
Nodes (16): Af(), co(), cr(), Dt(), gs(), handleClickHelp(), ht(), pt() (+8 more)

### Community 67 - "TinyGarden Angular Bundle 15"
Cohesion: 0.28
Nodes (16): capture(), consumeOptional(), jl(), parse(), parseChildren(), parseFragment(), parseMatrixParams(), parseParam() (+8 more)

### Community 72 - "LLM Chat Task Modules"
Cohesion: 0.23
Nodes (3): LlmAskAudioTask, LlmAskImageTask, LlmChatTask

### Community 73 - "Example Custom Task"
Cohesion: 0.20
Nodes (3): ExampleCustomTask, ExampleCustomTaskModelInstance, ExampleCustomTaskScreen()

### Community 76 - "Audio Playback Panel"
Cohesion: 0.23
Nodes (6): AmplitudeBarGraph(), AudioPlaybackPanel(), generateAmplitudeLevels(), playAudio(), stopPlayAudio(), MessageBodyAudioClip()

### Community 77 - "Model Page App Bar"
Cohesion: 0.14
Nodes (4): ModelPageAppBar(), ModelPickerChip(), LlmSingleTurnScreen(), VerticalSplitView()

### Community 78 - "Function Calling And Skill Publishing"
Cohesion: 0.21
Nodes (13): Skill Share Discussion Template, Skill Webhost Path (live web deployment URL), Deploy Skills to GitHub Pages Workflow, ActionType enum / Action class (Actions.kt), Custom Function Calling Guide, MobileActionsTask.getSystemPrompt, MobileActionsTools (@Tool/@ToolParam Toolset), MobileActionsViewModel.performAction (+5 more)

### Community 80 - "TinyGarden Angular Bundle 16"
Cohesion: 0.22
Nodes (3): getGlobalEventTarget(), onAndCancel(), runGuarded()

### Community 81 - "Add/Edit Skill Sheet"
Cohesion: 0.21
Nodes (4): AddOrEditSkillBottomSheet(), ScriptsTabContent(), CursorTrackingTextField(), GenerateLlmPromptBottomSheet()

### Community 83 - "Memory Warning And Download"
Cohesion: 0.23
Nodes (4): DownloadAndTryButton(), isMemoryLow(), MemoryWarningAlert(), ModelPicker()

### Community 86 - "Skill Tester Sheet"
Cohesion: 0.18
Nodes (5): SkillTesterBottomSheet(), PromptTemplate, SampleActionItem, Tab, MessageBodyWebview()

### Community 88 - "Home Screen Layout"
Cohesion: 0.35
Nodes (8): rememberDelayedAnimationProgress(), AppTitleGm4(), CategoryTabHeader(), HomeScreen(), IntroText(), TaskCard(), TaskList(), TryGm4IntroText()

### Community 90 - "Model Init Status"
Cohesion: 0.17
Nodes (7): ModelInitializationStatus, ModelInitializationStatusType, ERROR, INITIALIZED, INITIALIZING, NOT_INITIALIZED, ModelManagerUiState

### Community 91 - "JS Skill Entrypoint (assets)"
Cohesion: 0.17
Nodes (10): interactive-map ai_edge_gallery_get_result (Google Maps embed iframe), interactive-map Skill, qr-code ai_edge_gallery_get_result (QRCode.js to base64 PNG), qr-code Skill, query-wikipedia ai_edge_gallery_get_result, query-wikipedia Skill, webview init() (MediaPipe FaceLandmarker + camera), webview predict() (head-tracked spinning label) (+2 more)

### Community 92 - "Mood Tracker Dashboard (built-in)"
Cohesion: 0.23
Nodes (10): Mood Tracker dashboard.html, dashboard loadData(), renderTrendChart() (Chart.js), mood-tracker ai_edge_gallery_get_result (action dispatcher), getData(), localStorage key mood_tracker_data, resolveDate(), saveData() (+2 more)

### Community 93 - "Restaurant Roulette Skill"
Cohesion: 0.20
Nodes (11): require-secret API Key Metadata, animate() spin with friction and winner selection, Base64 Pipe-Delimited Places Query Param (c, l, data), draw() wheel canvas, triggerSpin(), Restaurant Roulette Preview Card, redirect() to ui.html, Restaurant Roulette Skill (README) (+3 more)

### Community 95 - "Audio Recorder Panel"
Cohesion: 0.22
Nodes (3): AudioRecorderPanel(), startRecording(), stopRecording()

### Community 96 - "JS Skill Entrypoint (built-in)"
Cohesion: 0.24
Nodes (9): interactive-map ai_edge_gallery_get_result(), qr-code ai_edge_gallery_get_result(), query-wikipedia ai_edge_gallery_get_result(), fetchWikiFuzzyAndInfobox(), Full-Screen 3D Head Tracker (webview.html), text-spinner ai_edge_gallery_get_result(), qrcodejs 1.0.0 (CDN), Skill webview response (webview.url) (+1 more)

### Community 100 - "Mood Music Skill"
Cohesion: 0.29
Nodes (8): Mood Music Webview Audio Player, Loudly API, Mood Music Skill (README), get_genres ai_edge_gallery_get_result(), mood-music index ai_edge_gallery_get_result(), Mood Music Local Test Handler, Mood Music Generation Payload (genre, genre_blend, duration, energy, bpm), mood-music Skill Definition

### Community 102 - "Intent Handler"
Cohesion: 0.31
Nodes (3): IntentHandler, SendEmailParams, SendSmsParams

### Community 107 - "Model Allowlist"
Cohesion: 0.43
Nodes (4): isPixel10(), AllowedModel, DefaultConfig, SocModelFile

### Community 108 - "Add Skill Options"
Cohesion: 0.25
Nodes (6): AddSkillOption, AddSkillOptionType, FeaturedList, LocalImport, ManualInput, RemoteUrl

### Community 109 - "Mobile Action Types"
Cohesion: 0.25
Nodes (8): ActionType, ACTION_CREATE_CALENDAR_EVENT, ACTION_CREATE_CONTACT, ACTION_FLASHLIGHT_OFF, ACTION_FLASHLIGHT_ON, ACTION_OPEN_WIFI_SETTINGS, ACTION_SEND_EMAIL, ACTION_SHOW_LOCATION_ON_MAP

### Community 112 - "Config Values"
Cohesion: 0.36
Nodes (7): ConfigValue, FloatValue, getFloatConfigValue(), getIntConfigValue(), getStringConfigValue(), IntValue, StringValue

### Community 113 - "App ToS Dialog"
Cohesion: 0.29
Nodes (3): AppTosDialog(), SettingsDialog(), themeLabel()

### Community 117 - "Virtual Piano Skill"
Cohesion: 0.33
Nodes (7): Piano Audio Engine (per-note Audio, pointer handlers), 88-Key Piano Keyboard Generator, Auto-scroll to Middle C, fuhton/piano-mp3 (MIT piano samples), Virtual Piano Skill (README), Virtual Piano Script Entry (loads index.js), virtual-piano Skill Definition

### Community 118 - "JSON Response Utils"
Cohesion: 0.33
Nodes (3): JsonObjAndTextContent, getJsonResponse(), parseJson()

### Community 119 - "Home Categories"
Cohesion: 0.47
Nodes (3): Category, CategoryInfo, getCategoryLabel()

### Community 120 - "Ask Audio DI Module"
Cohesion: 0.60
Nodes (3): LlmAskAudioModule, LlmAskImageModule, LlmChatTaskModule

### Community 129 - "Pocket Node Design Notes"
Cohesion: 0.40
Nodes (3): PocketNodeState.lastStartupError / isServerRunning observable state, PocketNodeService, PocketNodeState (activeChatModel / activeAudioModel)

### Community 131 - "Bug Reporting"
Cohesion: 0.67
Nodes (3): Bug Report Issue Template, adb bugreport, Android Full Bug Report Capture Guide

### Community 132 - "Text Spinner Face Tracking"
Cohesion: 1.00
Nodes (3): init(), predict(), MediaPipe FaceLandmarker (tasks-vision)

### Community 134 - "HuggingFace OAuth Setup"
Cohesion: 0.67
Nodes (3): appAuthRedirectScheme manifest placeholder, HuggingFace OAuth Developer Application, ProjectConfig.kt (clientId, redirectUri)

## Ambiguous Edges - Review These
- `skills/calculate-hash/scripts/index.js` → `ai_edge_gallery_get_result JS Skill Contract`  [AMBIGUOUS]
  Android/src/app/src/main/assets/skills/calculate-hash/scripts/index.html · relation: implements
- `built-in/calculate-hash/scripts/index.js` → `ai_edge_gallery_get_result JS skill entrypoint protocol`  [AMBIGUOUS]
  skills/built-in/calculate-hash/scripts/index.html · relation: implements
- `Mood Tracker Dashboard (dashboard.html)` → `Tiny Garden (Angular app shell)`  [AMBIGUOUS]
  Android/src/app/src/main/assets/tinygarden/index.html · relation: conceptually_related_to

## Knowledge Gaps
- **152 isolated node(s):** `CAPABILITY_SELECT`, `MODEL_DOWNLOAD`, `GENERATE_ACTION`, `BUTTON_CLICKED`, `CALL_JS_SKILL` (+147 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 695 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **65 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ModelManagerViewModel` connect `Model Manager ViewModel` to `Skill Manager Dialogs UI`, `Compose Shared UI Widgets`, `Analytics And Chat WebView`, `DataStore Serializers And DI`, `Model Config Editors`, `Camera And Audio Input UI`, `Benchmark Results Viewer`, `Settings And Config Dialogs`, `Home Screen And ToS Dialogs`, `Navigation And Custom Tasks`, `LLM Single-Turn ViewModels`, `MainActivity And Theme`, `Message Body Rendering`, `Chat Panel Scrolling`, `HuggingFace Auth Tokens`, `Task Registry And Main UI`, `Model Download Status UI`, `Agent Chat Screen WebView`, `TinyGarden Tools`, `DataStore Repository Interface`, `Accordion Result Views`, `UI Common Utils`, `Task Icons And Global Models`, `Message Input Handling`, `Mobile Actions Task`, `App Bar Actions`, `LLM Chat ViewModel`, `Example Custom Task`, `Mobile Actions VM Wiring`, `Model Page App Bar`, `Custom Task Interface`, `Memory Warning And Download`, `Home Screen Layout`, `Model Init Status`, `Pocket Node Model Resolver`, `Pocket Node State And Dialog`, `App ToS Dialog`, `Gallery App Root`?**
  _High betweenness centrality (0.063) - this node is a cross-community bridge._
- **What connects `CAPABILITY_SELECT`, `MODEL_DOWNLOAD`, `GENERATE_ACTION` to the rest of the system?**
  _152 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `TinyGarden Angular Bundle 1` be split into smaller, more focused modules?**
  _Cohesion score 0.017096493769921763 - nodes in this community are weakly interconnected._
- **Why does `DataStoreRepository` connect `DataStore Repository Interface` to `Benchmark Results Storage`, `DataStore Serializers And DI`, `Cutouts Storage`, `Skills Storage`, `Gallery Application`, `Benchmark Results Viewer`, `Model Manager ViewModel`, `Skill Allowlist Loading`, `DataStore Repository Impl`, `ToS ViewModel`, `TinyGarden ViewModel`, `Access Token Storage`, `Skill Manager ViewModel`, `Imported Models Storage`, `Theme Storage`, `HuggingFace Auth Tokens`?**
  _High betweenness centrality (0.062) - this node is a cross-community bridge._
- **Should `TinyGarden Angular Bundle 2` be split into smaller, more focused modules?**
  _Cohesion score 0.05404551201011378 - nodes in this community are weakly interconnected._

### Low-confidence Hints
_AMBIGUOUS edges — the extractor was unsure. Verify before acting on these._

- **What is the exact relationship between `skills/calculate-hash/scripts/index.js` and `ai_edge_gallery_get_result JS Skill Contract`?**
  _Edge tagged AMBIGUOUS (relation: implements) - confidence is low._
- **What is the exact relationship between `built-in/calculate-hash/scripts/index.js` and `ai_edge_gallery_get_result JS skill entrypoint protocol`?**
  _Edge tagged AMBIGUOUS (relation: implements) - confidence is low._