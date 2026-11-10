# Coding-agent asset integrity contract

The JARVIS coding agent deliberately reuses its own provider configuration and tool runtime. The original Clozed provider catalog (providers.json, 211 providers) is not shipped; JARVIS resolves a provider through ProviderRuntime.manager(app).resolveChat(WorkCategory.CODING).

## Required recovered prompt assets

These seven text files must be copied byte-for-byte from the recovered Clozed source. Do not recreate, paraphrase, normalize line endings, or edit them:

| Asset | Bytes | SHA-256 |
|---|---:|---|
| prompts/system.txt | 757 | 5a56eb8ea3da1556b0ae3482bb31a6f029ad914a97a1aea53d511e927f0e4e96 |
| prompts/anthropic.txt | 300 | 0ae1276d0af9abb456a0f96409b32beb1b081227db7a3d0f236f919e4fd29bac |
| prompts/gpt.txt | 2573 | c02a0b424b2883578e016fa33179bd359add8358fec1781f211a0c9e70405040 |
| prompts/gpt-astra.txt | 3826 | 721c1d4add140e9b0fe19d2118366a7a42651d9584ce0513fbc3d9e780adbff0 |
| prompts/kimi.txt | 8704 | cd2cc4a9154a0875fcbee3b18c27a620c8965c8cf86f02fa0c041e7806225361 |
| prompts/meta.txt | 7859 | 4722b71e0b160597e3e783ce514cd3283f7f2fba87de9a868be5817f13ac7bce |
| prompts/trinity.txt | 7687 | 7af5b306ac4ab624083e97e1f540c41e18c9036887877a7ff15fa01e4b50d683 |

**Current repository status:** the files above are not present in app/src/main/assets/prompts/. Their source bytes were not included in the handoff documents available to this change, so no substitute files have been fabricated. The engine now refuses a coding turn when prompts/system.txt is absent or blank and surfaces a visible notice. A missing model addendum is reported by asset path and the base prompt is used. The hash manifest remains an acceptance gate until the recovered originals are supplied.

## Required coding-agent skills

Place the five original skill files under the dedicated nested path below so they do not collide with JARVIS's existing flat assets/skills/*.md skill catalog:

- skills/clozed/code-review/SKILL.md — SHA-256 37925612abcdef9b6c610b06f0c2e997142f56c410de606702c794f62cdd7043
- skills/clozed/port-from-project/SKILL.md — SHA-256 c60b38c4127d846399f38672257ebb5e9049e0f352de33bca9c38839a1a9fe41
- skills/clozed/skill-creator/SKILL.md — SHA-256 a41fbc5e23d75be2df87dbc7e884dea1697138a486adb713818fb65072373ce3
- skills/clozed/spike/SKILL.md — SHA-256 b071d34531f6c824e4571d2dbf4101bb74eaa30d8714152d91d978b5942e7c6b
- skills/clozed/systematic-debugging/SKILL.md — SHA-256 ca77bb37705c670253886f7d97945fe8d8c0c7ec1728f21c58552621e861bc87

SkillStore supports this nested layout and parses each SKILL.md using the existing JARVIS skill front-matter parser. The original files are still absent, so their hashes are not claimed as verified.

## Deliberate omissions

- providers.json: not required; provider/model selection is owned by JARVIS's coding work route.
- Clozed Compose theme assets: not required; JARVIS uses its existing XML/viewBinding UI.
- Existing JARVIS skills in flat assets/skills/*.md: preserved and continue to load.

## Verification

From app/src/main/assets, run sha256sum -c against the manifest above after the original files are restored. Every line must report OK. Then inspect the packaged APK with unzip -l for assets/prompts/ and assets/skills/clozed/. Do not mark this contract complete until both byte hashes and package contents are verified.
