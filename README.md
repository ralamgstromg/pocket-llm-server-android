# Pocket Node (Google AI-Edge-gallery)✨
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Turn your Android phone into a true, offline AI network node.** 

Pocket Node is a customized fork of the open-source **[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)**. It takes the lightning-fast, on-device LiteRT inference engine of the original app and seamlessly bridges it to an embedded HTTP Server via Ktor.

Instead of burning your PC's GPU power or paying for external cloud APIs, you can now expose your phone's offline LLMs directly to your local WiFi network!

---

## 🚀 The Power of Pocket Node

*   **API on the Go**: Pocket Node exposes a background network service on `http://YOUR_PHONE_IP:8080/v1/chat/completions`.
*   **OpenAI-Compatible Schema**: Designed to cleanly integrate with your existing scripts, Python backends, and low-code wrappers (like `n8n`) using the industry standard JSON schema.
*   **Zero Cloud, Zero Cost**: 100% on-device inference using your Android's NPU/GPU. Completely offline and private.

## 🏁 Get Started in Minutes

1. **Clone & Build**: Clone this repository and build the APK using Android Studio.
2. **Launch a Model**: Open the app and tap **Chat** to load a model (like Gemma 4) into memory.
3. **Ignite the Server**: Open the left-side navigation drawer and tap **Pocket Node Server** instantly turn your phone into a server!
4. **Test it out**:
   From any PC on your local network, fire this `curl` command using your mobile IP:
   ```bash
   curl http://<YOUR_PHONE_IP>:8080/v1/chat/completions \
     -H "Content-Type: application/json" \
     -d '{
       "messages": [
         {
           "role": "user",
           "content": "Explain quantum computing in one sentence."
         }
       ]
     }'
   ```

## 📖 Upstream Features & Documentation

This project retains all the amazing conversational features of the standalone app, including Agentic Skills, Multimodal Image Scanning, and Audio Scribes! 

To explore the original user interface capabilities and architectural deep-dives, please read the full documentation on Google's master repository:

👉 **[Google AI Edge Gallery Official Repo & Wiki](https://github.com/google-ai-edge/gallery)**

---

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.
