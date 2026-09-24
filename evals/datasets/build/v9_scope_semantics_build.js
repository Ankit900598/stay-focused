/**
 * Builder for evals/datasets/v9_scope_semantics.jsonl.
 *
 * Deterministic — no randomness. Regenerate with:
 *   node evals/datasets/build/v9_scope_semantics_build.js
 *
 * Every emitted row is a self-contained expected-scope case:
 *   { id, userPromise, language, cluster, expectedScope: { kind, identity, host?,
 *     surfaces?, sites?, packages?, packageMatch?, resolution, evidenceSpans,
 *     unresolvedReason?, appliesToText? } }
 *
 * The dataset is consumed by:
 *   - backend/smoke_scope_semantics_v1.js (structural invariants)
 *   - a future model runner (Phase 2/3) for exact-kind-match + wrong-host metrics
 *
 * Coverage targets (docs/scope-semantics-v1.md §6.1, §7 Phase 1):
 *   - All 11 kinds (10 enforceable + UNRESOLVED)
 *   - Every §5.2 ambiguity trigger among UNRESOLVED rows
 *   - Mix of en / hinglish / hi / voice-transcript variants
 *   - Common typos: "shots", "sorts", "insta", "yt", "fb", "wp", "chorme"
 *   - No package IDs in any userPromise or expected.appliesToText
 */
"use strict";

const fs = require("fs");
const path = require("path");

const OUT_PATH = path.join(__dirname, "..", "v9_scope_semantics.jsonl");

const rows = [];

function push(row) {
  if (!row.id || typeof row.id !== "string") throw new Error("row.id required");
  if (!row.userPromise) throw new Error(`${row.id}: userPromise required`);
  const exp = row.expectedScope;
  if (!exp || !exp.kind) throw new Error(`${row.id}: expectedScope.kind required`);
  exp.resolution = exp.resolution || "EXCLUSIVE";
  exp.evidenceSpans = Array.isArray(exp.evidenceSpans) ? exp.evidenceSpans : [];
  // Sanity: every evidenceSpan is a verbatim substring of userPromise.
  for (const span of exp.evidenceSpans) {
    if (!row.userPromise.includes(span) && !row.userPromise.toLowerCase().includes(span.toLowerCase())) {
      throw new Error(`${row.id}: evidenceSpan not verbatim: '${span}' in '${row.userPromise}'`);
    }
  }
  rows.push({
    language: row.language || "en",
    cluster: row.cluster || "phase1_seed",
    ...row,
  });
}

function seq(prefix, entries) {
  entries.forEach((entry, i) => {
    const idx = String(i + 1).padStart(2, "0");
    push({ id: `${prefix}_${idx}`, ...entry });
  });
}

// ----- APP_ONLY (10) --------------------------------------------------------

seq("v9_app_only", [
  {
    userPromise: "YouTube app only for 1 hour, block videos shorter than 30 min",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["YouTube app only"],
      appliesToText: "the YouTube app only",
    },
  },
  {
    userPromise: "block me from the Instagram app for 2 hours",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram app"],
      appliesToText: "the Instagram app only",
    },
  },
  {
    userPromise: "TikTok app only, 45 minutes",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "TIKTOK",
      evidenceSpans: ["TikTok app only"],
      appliesToText: "the TikTok app only",
    },
    language: "en",
  },
  {
    userPromise: "sirf Snapchat app band karo 1 ghante ke liye",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "SNAPCHAT",
      evidenceSpans: ["Snapchat app"],
      appliesToText: "the Snapchat app only",
    },
    language: "hinglish",
  },
  {
    userPromise: "only WhatsApp app during 9pm–11pm study",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "WHATSAPP",
      evidenceSpans: ["only WhatsApp app"],
      appliesToText: "the WhatsApp app only",
    },
  },
  {
    userPromise: "Facebook app hi block karna hai, browser nahi",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["Facebook app"],
      appliesToText: "the Facebook app only",
    },
    language: "hinglish",
  },
  {
    userPromise: "yt app only for the next hour",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["yt app only"],
      appliesToText: "the YouTube app only",
    },
  },
  {
    userPromise: "insta app only for 30 min then done",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["insta app only"],
      appliesToText: "the Instagram app only",
    },
  },
  {
    userPromise: "block Messenger app till midnight",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "MESSENGER",
      evidenceSpans: ["Messenger app"],
      appliesToText: "the Messenger app only",
    },
  },
  {
    userPromise: "um, so, YouTube app only, no browser, for like an hour",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["YouTube app only"],
      appliesToText: "the YouTube app only",
    },
    cluster: "voice_transcript",
  },
]);

// ----- BROWSER_ONLY (10) ---------------------------------------------------

seq("v9_browser_only", [
  {
    userPromise: "Only YouTube in Chrome, not the app, for 1 hour",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["Only YouTube in Chrome, not the app"],
      appliesToText: "YouTube pages inside your browser",
    },
  },
  {
    userPromise: "YouTube only in my browser, keep the app free",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["YouTube only in my browser"],
      appliesToText: "YouTube pages inside your browser",
    },
  },
  {
    userPromise: "Instagram in Chrome only, no app",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram in Chrome only"],
      appliesToText: "Instagram pages inside your browser",
    },
  },
  {
    userPromise: "facebook browser only, app is fine",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["facebook browser only"],
      appliesToText: "Facebook pages inside your browser",
    },
  },
  {
    userPromise: "TikTok only in Firefox for tonight",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "TIKTOK",
      evidenceSpans: ["TikTok only in Firefox"],
      appliesToText: "TikTok pages inside your browser",
    },
  },
  {
    userPromise: "sirf browser me youtube band karo",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["browser me youtube"],
      appliesToText: "YouTube pages inside your browser",
    },
    language: "hinglish",
  },
  {
    userPromise: "block yt in chorme for 30 mins",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["yt in chorme"],
      appliesToText: "YouTube pages inside your browser",
    },
    cluster: "typo",
  },
  {
    userPromise: "snap in browser only, app allowed",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "SNAPCHAT",
      evidenceSpans: ["snap in browser only"],
      appliesToText: "Snapchat pages inside your browser",
    },
  },
  {
    userPromise: "watch YouTube in Brave only, not the YT app",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["YouTube in Brave only"],
      appliesToText: "YouTube pages inside your browser",
    },
  },
  {
    userPromise: "Instagram in browser only, app off, for 1 hour",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram in browser only"],
      appliesToText: "Instagram pages inside your browser",
    },
  },
]);

// ----- CONTENT_ANY_HOST (10) -----------------------------------------------

seq("v9_content_any_host", [
  {
    userPromise: "No YouTube videos anywhere for 2 hours",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "YOUTUBE",
      evidenceSpans: ["YouTube videos anywhere"],
      appliesToText: "YouTube videos anywhere",
    },
  },
  {
    userPromise: "block all YouTube content, app or browser, doesn't matter",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "YOUTUBE",
      evidenceSpans: ["all YouTube content, app or browser"],
      appliesToText: "YouTube videos anywhere",
    },
  },
  {
    userPromise: "no Instagram anywhere for the next hour",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram anywhere"],
      appliesToText: "Instagram anywhere",
    },
  },
  {
    userPromise: "TikTok anywhere, kill it for 3 hours",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "TIKTOK",
      evidenceSpans: ["TikTok anywhere"],
      appliesToText: "TikTok anywhere",
    },
  },
  {
    userPromise: "kahin bhi YouTube band, app ho ya browser",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "YOUTUBE",
      evidenceSpans: ["kahin bhi YouTube"],
      appliesToText: "YouTube videos anywhere",
    },
    language: "hinglish",
  },
  {
    userPromise: "Facebook everywhere for 1 hour, app browser all",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "FACEBOOK",
      evidenceSpans: ["Facebook everywhere"],
      appliesToText: "Facebook anywhere",
    },
  },
  {
    userPromise: "block snap everywhere until 10pm",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "SNAPCHAT",
      evidenceSpans: ["snap everywhere"],
      appliesToText: "Snapchat anywhere",
    },
  },
  {
    userPromise: "no WhatsApp anywhere during study session",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "WHATSAPP",
      evidenceSpans: ["WhatsApp anywhere"],
      appliesToText: "WhatsApp anywhere",
    },
  },
  {
    userPromise: "Messenger anywhere, keep me off it for 90 min",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "MESSENGER",
      evidenceSpans: ["Messenger anywhere"],
      appliesToText: "Messenger anywhere",
    },
  },
  {
    userPromise: "no yt content anywhere for the next hour please",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "YOUTUBE",
      evidenceSpans: ["yt content anywhere"],
      appliesToText: "YouTube videos anywhere",
    },
  },
]);

// ----- VIDEO_CLASS_ANY_HOST (8) --------------------------------------------

seq("v9_video_class", [
  {
    userPromise: "no video apps at all for 2 hours",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["no video apps"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "block all major video apps until 9pm",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["all major video apps"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "no video watching anywhere for 1 hour",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["video watching anywhere"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "video apps sab band, browser me bhi",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["video apps sab band"],
      appliesToText: "video everywhere",
    },
    language: "hinglish",
  },
  {
    userPromise: "no streaming apps at all tonight",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["no streaming apps"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "block every video player for 90 min",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["every video player"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "kill all video during study",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["all video"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "no youtube newpipe tiktok anything video, 2 hours",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["anything video"],
      appliesToText: "video everywhere",
    },
  },
]);

// ----- SHORT_FORM_ANY_HOST (12) -------------------------------------------

seq("v9_short_form", [
  {
    userPromise: "no short videos for 2 hours",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["short videos"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "at most 10 shorts today, no reels no tiktok extra",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["10 shorts today"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "block shorts everywhere for 3 hours",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["shorts everywhere"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "no reels or shorts today",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["reels or shorts"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "allow me upto 20 shots today",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["20 shots today"],
      appliesToText: "short videos everywhere",
    },
    cluster: "typo_shots",
  },
  {
    userPromise: "no sorts videos for 1 hour",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["sorts videos"],
      appliesToText: "short videos everywhere",
    },
    cluster: "typo_sorts",
  },
  {
    userPromise: "no tiktok-style clips for 1 hour",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["tiktok-style clips"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "short videos sab band karo abhi",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["short videos sab band"],
      appliesToText: "short videos everywhere",
    },
    language: "hinglish",
  },
  {
    userPromise: "10 shorts today then done",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["10 shorts today"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "no short-form video today, all apps",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["short-form video"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "reels off for 2 hours everywhere",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["reels off"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "no shorts on any app for 1 hour",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["shorts on any app"],
      appliesToText: "short videos everywhere",
    },
  },
]);

// ----- SOCIAL_MESSAGING_ONLY (10) ------------------------------------------

seq("v9_social_messaging_only", [
  {
    userPromise: "Instagram messages only, no Reels",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram messages only, no Reels"],
      appliesToText: "Instagram — messages stay open",
    },
  },
  {
    userPromise: "IG DMs only, feed and reels blocked",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["IG DMs only"],
      appliesToText: "Instagram — messages stay open",
    },
  },
  {
    userPromise: "Facebook messenger allowed, feed blocked",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["messenger allowed, feed blocked"],
      appliesToText: "Facebook — messages stay open",
    },
  },
  {
    userPromise: "Snapchat chats only, no stories or spotlight",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "SNAPCHAT",
      evidenceSpans: ["Snapchat chats only"],
      appliesToText: "Snapchat — chats stay open",
    },
  },
  {
    userPromise: "insta me messages hi allow, baaki band",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["messages hi allow"],
      appliesToText: "Instagram — messages stay open",
    },
    language: "hinglish",
  },
  {
    userPromise: "only DMs on IG for study, block reels and feed",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["only DMs on IG"],
      appliesToText: "Instagram — messages stay open",
    },
  },
  {
    userPromise: "Facebook chats only, hide the feed",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["Facebook chats only"],
      appliesToText: "Facebook — chats stay open",
    },
  },
  {
    userPromise: "TikTok DMs only, no For You",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "TIKTOK",
      evidenceSpans: ["TikTok DMs only"],
      appliesToText: "TikTok — DMs stay open",
    },
  },
  {
    userPromise: "let me talk on IG, block scrolling",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["talk on IG"],
      appliesToText: "Instagram — messages stay open",
    },
  },
  {
    userPromise: "Instagram — messages only, please, for tonight",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["messages only"],
      appliesToText: "Instagram — messages stay open",
    },
  },
]);

// ----- SOCIAL_FEED_AND_REELS_ONLY (10) -------------------------------------

seq("v9_feed_and_reels_only", [
  {
    userPromise: "Block the Facebook feed but let Messenger work",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["Block the Facebook feed but let Messenger work"],
      appliesToText: "the Facebook feed and reels — Messenger stays open",
    },
  },
  {
    userPromise: "block Instagram feed and reels, keep Threads",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram feed and reels"],
      appliesToText: "the Instagram feed and reels",
    },
  },
  {
    userPromise: "FB feed off, messenger fine",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["FB feed off, messenger fine"],
      appliesToText: "the Facebook feed and reels — Messenger stays open",
    },
  },
  {
    userPromise: "Instagram feed blocked but DMs allowed",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram feed blocked but DMs allowed"],
      appliesToText: "the Instagram feed and reels — DMs stay open",
    },
  },
  {
    userPromise: "TikTok For You blocked, DMs open",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "TIKTOK",
      evidenceSpans: ["TikTok For You blocked, DMs open"],
      appliesToText: "the TikTok For You feed",
    },
  },
  {
    userPromise: "Snapchat spotlight off, chats fine",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "SNAPCHAT",
      evidenceSpans: ["Snapchat spotlight off, chats fine"],
      appliesToText: "the Snapchat spotlight feed",
    },
  },
  {
    userPromise: "Facebook reels off, messenger allowed",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["Facebook reels off, messenger allowed"],
      appliesToText: "the Facebook feed and reels — Messenger stays open",
    },
  },
  {
    userPromise: "Insta feed reels bandh karo, DMs chalu",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Insta feed reels bandh karo"],
      appliesToText: "the Instagram feed and reels — DMs stay open",
    },
    language: "hinglish",
  },
  {
    userPromise: "block the FB feed and reels, everything else fine",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["FB feed and reels"],
      appliesToText: "the Facebook feed and reels",
    },
  },
  {
    userPromise: "IG feed and reels only, no messaging surfaces",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["IG feed and reels only"],
      appliesToText: "the Instagram feed and reels",
    },
  },
]);

// ----- SITE_SCOPED (10) ----------------------------------------------------

seq("v9_site_scoped", [
  {
    userPromise: "only allow nesoacademy.org in Chrome tonight",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["nesoacademy.org"],
      evidenceSpans: ["nesoacademy.org"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "study session: only nptel.ac.in and gate-cse.org",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["nptel.ac.in", "gate-cse.org"],
      evidenceSpans: ["nptel.ac.in and gate-cse.org"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "browser only for leetcode.com for 1 hour",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["leetcode.com"],
      evidenceSpans: ["leetcode.com"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "chrome only on codeforces.com",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["codeforces.com"],
      evidenceSpans: ["codeforces.com"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "browser only for docs.google.com and github.com today",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["docs.google.com", "github.com"],
      evidenceSpans: ["docs.google.com and github.com"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "sirf khan academy sites, kuch aur nahi",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["khanacademy.org"],
      evidenceSpans: ["khan academy"],
      appliesToText: "specific sites in your browser only",
    },
    language: "hinglish",
  },
  {
    userPromise: "only atcoder.jp for the contest",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["atcoder.jp"],
      evidenceSpans: ["atcoder.jp"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "chrome only allowed on stackoverflow.com",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["stackoverflow.com"],
      evidenceSpans: ["stackoverflow.com"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "browser only for university.edu today",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["university.edu"],
      evidenceSpans: ["university.edu"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "let me have nptel.ac.in only in browser",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["nptel.ac.in"],
      evidenceSpans: ["nptel.ac.in"],
      appliesToText: "specific sites in your browser only",
    },
  },
]);

// ----- HOST_WITH_CATEGORY_EXEMPTIONS (10) ---------------------------------

seq("v9_host_with_exemptions", [
  {
    userPromise: "Chrome is fine except adult, movie, and video sites",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["except adult, movie, and video sites"],
      appliesToText: "adult, movie, and video pages in your browser",
    },
  },
  {
    userPromise: "browser allowed but block porn and movies",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["block porn and movies"],
      appliesToText: "adult and movie pages in your browser",
    },
  },
  {
    userPromise: "chrome fine except netflix hotstar and porn",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["except netflix hotstar and porn"],
      appliesToText: "adult, movie, and video pages in your browser",
    },
  },
  {
    userPromise: "normal browsing OK, block adult and streaming pages",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["block adult and streaming pages"],
      appliesToText: "adult and streaming pages in your browser",
    },
  },
  {
    userPromise: "let chrome work but stop movie sites and video players",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["movie sites and video players"],
      appliesToText: "movie and video pages in your browser",
    },
  },
  {
    userPromise: "browser fine except adult content",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["except adult content"],
      appliesToText: "adult pages in your browser",
    },
  },
  {
    userPromise: "chrome me sirf porn aur movie block",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["porn aur movie block"],
      appliesToText: "adult and movie pages in your browser",
    },
    language: "hinglish",
  },
  {
    userPromise: "keep chrome open, stop netflix jio hotstar and any player",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["stop netflix jio hotstar"],
      appliesToText: "streaming and video pages in your browser",
    },
  },
  {
    userPromise: "browser fine but no adult, ott, video pages",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["no adult, ott, video pages"],
      appliesToText: "adult, movie, and video pages in your browser",
    },
  },
  {
    userPromise: "chrome ok, block ott pages and adult",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["block ott pages and adult"],
      appliesToText: "adult and streaming pages in your browser",
    },
  },
]);

// ----- PACKAGE_PINNED (10) --------------------------------------------------

seq("v9_package_pinned", [
  {
    userPromise: "block NetMirror",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "block NetMirror premium clone app",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror premium clone app"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "lock this NetMirror app permanently",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror app permanently"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "block just the NetMirror app for 2 hours",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror app"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "kill NetMirror till midnight",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "block NewTV app tonight",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NewTV app"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "lock NetMirror only, nothing else",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror only"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "block that new tv movie app",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["new tv movie app"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "NetMirror band karo abhi",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror band karo"],
      appliesToText: "specific apps you named",
    },
    language: "hinglish",
  },
  {
    userPromise: "lock the NetMirror app for 30 days",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["NetMirror app for 30 days"],
      appliesToText: "specific apps you named",
    },
  },
]);

// ----- UNRESOLVED (~30, one per §5.2 trigger, plus filler) -----------------

const unresolved = [
  // HOST_AMBIGUOUS
  {
    userPromise: "only YouTube",
    reason: "HOST_AMBIGUOUS",
    evidenceSpans: ["only YouTube"],
  },
  {
    userPromise: "block Instagram",
    reason: "HOST_AMBIGUOUS",
    evidenceSpans: ["block Instagram"],
  },
  {
    userPromise: "only TikTok",
    reason: "HOST_AMBIGUOUS",
    evidenceSpans: ["only TikTok"],
  },
  {
    userPromise: "sirf youtube",
    reason: "HOST_AMBIGUOUS",
    evidenceSpans: ["sirf youtube"],
    language: "hinglish",
  },
  {
    userPromise: "block yt",
    reason: "HOST_AMBIGUOUS",
    evidenceSpans: ["block yt"],
  },
  // IDENTITY_SCOPE_AMBIGUOUS
  {
    userPromise: "no video for 1 hour",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["no video"],
  },
  {
    userPromise: "block Google video",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["Google video"],
  },
  {
    userPromise: "stop videos",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["stop videos"],
  },
  {
    userPromise: "google video band karo",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["google video"],
    language: "hinglish",
  },
  // BRAND_VS_CATEGORY
  {
    userPromise: "no Instagram reels",
    reason: "BRAND_VS_CATEGORY",
    evidenceSpans: ["Instagram reels"],
  },
  {
    userPromise: "no YouTube shorts",
    reason: "BRAND_VS_CATEGORY",
    evidenceSpans: ["YouTube shorts"],
  },
  {
    userPromise: "block Facebook reels",
    reason: "BRAND_VS_CATEGORY",
    evidenceSpans: ["Facebook reels"],
  },
  // SURFACE_AMBIGUOUS
  {
    userPromise: "block Facebook",
    reason: "SURFACE_AMBIGUOUS",
    evidenceSpans: ["block Facebook"],
  },
  {
    userPromise: "block Snapchat",
    reason: "SURFACE_AMBIGUOUS",
    evidenceSpans: ["block Snapchat"],
  },
  {
    userPromise: "stop Instagram",
    reason: "SURFACE_AMBIGUOUS",
    evidenceSpans: ["stop Instagram"],
  },
  // COMPANION_APP_AMBIGUOUS
  {
    userPromise: "block Facebook and its messages",
    reason: "COMPANION_APP_AMBIGUOUS",
    evidenceSpans: ["Facebook and its messages"],
  },
  {
    userPromise: "block Instagram and Threads",
    reason: "COMPANION_APP_AMBIGUOUS",
    evidenceSpans: ["Instagram and Threads"],
  },
  // SITE_LIST_UNRESOLVED
  {
    userPromise: "block adult sites",
    reason: "SITE_LIST_UNRESOLVED",
    evidenceSpans: ["adult sites"],
  },
  {
    userPromise: "block movie sites",
    reason: "SITE_LIST_UNRESOLVED",
    evidenceSpans: ["movie sites"],
  },
  {
    userPromise: "block streaming websites",
    reason: "SITE_LIST_UNRESOLVED",
    evidenceSpans: ["streaming websites"],
  },
  // EXEMPTION_UNCLEAR
  {
    userPromise: "block everything except the important stuff",
    reason: "EXEMPTION_UNCLEAR",
    evidenceSpans: ["except the important stuff"],
  },
  {
    userPromise: "no phone except what I need",
    reason: "EXEMPTION_UNCLEAR",
    evidenceSpans: ["except what I need"],
  },
  {
    userPromise: "block social except that one",
    reason: "EXEMPTION_UNCLEAR",
    evidenceSpans: ["except that one"],
  },
  // COMPOUND_SCOPE_CONFLICT
  {
    userPromise: "allow only YouTube and allow only Chrome",
    reason: "COMPOUND_SCOPE_CONFLICT",
    evidenceSpans: ["allow only YouTube and allow only Chrome"],
  },
  {
    userPromise: "only study apps but also normal phone",
    reason: "COMPOUND_SCOPE_CONFLICT",
    evidenceSpans: ["only study apps but also normal phone"],
  },
  {
    userPromise: "allow only IG and allow only WhatsApp",
    reason: "COMPOUND_SCOPE_CONFLICT",
    evidenceSpans: ["allow only IG and allow only WhatsApp"],
  },
  // Voice / typo / low-transcript-confidence variants
  {
    userPromise: "um so like block youtube i guess",
    reason: "HOST_AMBIGUOUS",
    evidenceSpans: ["block youtube"],
    language: "voice",
  },
  {
    userPromise: "no more video things you know",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["video things"],
    language: "voice",
  },
  {
    userPromise: "block bad apps",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["bad apps"],
  },
  {
    userPromise: "distracting stuff off",
    reason: "IDENTITY_SCOPE_AMBIGUOUS",
    evidenceSpans: ["distracting stuff"],
  },
];

seq("v9_unresolved", unresolved.map((u) => ({
  userPromise: u.userPromise,
  language: u.language || "en",
  cluster: `unresolved_${u.reason.toLowerCase()}`,
  expectedScope: {
    kind: "UNRESOLVED",
    identity: null,
    evidenceSpans: u.evidenceSpans,
    unresolvedReason: u.reason,
  },
})));

// ----- Extra diversity: mixed language / voice / typos (25) ----------------

seq("v9_diverse", [
  {
    userPromise: "youtube ka app hi block, chrome browser mein chalega",
    language: "hinglish",
    cluster: "app_only_hinglish",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["youtube ka app hi block"],
      appliesToText: "the YouTube app only",
    },
  },
  {
    userPromise: "kevlam youtube app matum block pannu",
    language: "tanglish",
    cluster: "app_only_tanglish",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["youtube app matum"],
      appliesToText: "the YouTube app only",
    },
  },
  {
    userPromise: "insta reels off pls, DMs ok",
    cluster: "social_feed_reels_typo",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["insta reels off"],
      appliesToText: "the Instagram feed and reels — DMs stay open",
    },
  },
  {
    userPromise: "yaar sirf study sites chahiye chrome me",
    language: "hinglish",
    cluster: "site_scoped_hinglish",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["study.example.com"],
      evidenceSpans: ["sirf study sites"],
      appliesToText: "specific study sites in your browser only",
    },
  },
  {
    userPromise: "no shorts on youtube reels on insta tiktok for you all short",
    cluster: "short_form_multi",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["no shorts on youtube reels on insta tiktok for you"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "block short-form 3 hrs pls",
    cluster: "short_form_short",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["short-form"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "browser me youtube dekhne do, app mat khulne do",
    language: "hinglish",
    cluster: "browser_only_hinglish",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "YOUTUBE",
      evidenceSpans: ["browser me youtube dekhne do"],
      appliesToText: "YouTube pages inside your browser",
    },
  },
  {
    userPromise: "block ig everywhere na, app browser sab",
    language: "hinglish",
    cluster: "any_host_hinglish",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "INSTAGRAM",
      evidenceSpans: ["ig everywhere"],
      appliesToText: "Instagram anywhere",
    },
  },
  {
    userPromise: "chrome me messenger allowed, feed off",
    language: "hinglish",
    cluster: "feed_only_hinglish",
    expectedScope: {
      kind: "SOCIAL_FEED_AND_REELS_ONLY",
      identity: "FACEBOOK",
      evidenceSpans: ["messenger allowed, feed off"],
      appliesToText: "the Facebook feed — Messenger stays open",
    },
  },
  {
    userPromise: "video anywhere block for 2 hours",
    cluster: "video_class_short",
    expectedScope: {
      kind: "VIDEO_CLASS_ANY_HOST",
      identity: "VIDEO_ANY",
      evidenceSpans: ["video anywhere block"],
      appliesToText: "video everywhere",
    },
  },
  {
    userPromise: "block porn always in chrome, rest fine",
    cluster: "host_exempt_adult_only",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["block porn always in chrome"],
      appliesToText: "adult pages in your browser",
    },
  },
  {
    userPromise: "netflix hotstar block in browser",
    cluster: "host_exempt_movie",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["netflix hotstar block"],
      appliesToText: "streaming pages in your browser",
    },
  },
  {
    userPromise: "only tiktok app for 20 min",
    cluster: "app_only_tiktok",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "TIKTOK",
      evidenceSpans: ["only tiktok app"],
      appliesToText: "the TikTok app only",
    },
  },
  {
    userPromise: "sirf whatsapp allow, kuch aur nahi",
    language: "hinglish",
    cluster: "app_only_wp",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "WHATSAPP",
      evidenceSpans: ["sirf whatsapp"],
      appliesToText: "the WhatsApp app only",
    },
  },
  {
    userPromise: "block movies + adult in browser, rest fine",
    cluster: "host_exempt_movie_adult",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["movies + adult in browser"],
      appliesToText: "adult and movie pages in your browser",
    },
  },
  {
    userPromise: "only leetcode dot com in chrome tonight",
    cluster: "site_scoped_voice",
    language: "voice",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["leetcode.com"],
      evidenceSpans: ["only leetcode dot com"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "no youtube shorts, no reels, no tiktok - short stuff off",
    cluster: "short_form_combo",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["short stuff off"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "browser only for github and stackoverflow, rest browser off",
    cluster: "site_scoped_multi",
    expectedScope: {
      kind: "SITE_SCOPED",
      identity: "ANY",
      sites: ["github.com", "stackoverflow.com"],
      evidenceSpans: ["github and stackoverflow"],
      appliesToText: "specific sites in your browser only",
    },
  },
  {
    userPromise: "block that netmirror thing, permanently",
    cluster: "package_pinned_voice",
    language: "voice",
    expectedScope: {
      kind: "PACKAGE_PINNED",
      identity: "ANY",
      packages: ["app.netmirror.newtv"],
      packageMatch: "PINNED",
      evidenceSpans: ["netmirror thing"],
      appliesToText: "specific apps you named",
    },
  },
  {
    userPromise: "IG me sirf messages, feed reels sab band",
    language: "hinglish",
    cluster: "messaging_only_hinglish",
    expectedScope: {
      kind: "SOCIAL_MESSAGING_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["IG me sirf messages"],
      appliesToText: "Instagram — messages stay open",
    },
  },
  {
    userPromise: "no tiktok anywhere for 4 hrs",
    cluster: "any_host_tiktok",
    expectedScope: {
      kind: "CONTENT_ANY_HOST",
      identity: "TIKTOK",
      evidenceSpans: ["tiktok anywhere"],
      appliesToText: "TikTok anywhere",
    },
  },
  {
    userPromise: "block Instagram in browser for 1 hour",
    cluster: "browser_only_ig",
    expectedScope: {
      kind: "BROWSER_ONLY",
      identity: "INSTAGRAM",
      evidenceSpans: ["Instagram in browser"],
      appliesToText: "Instagram pages inside your browser",
    },
  },
  {
    userPromise: "only Snapchat app 30 min please",
    cluster: "app_only_snap",
    expectedScope: {
      kind: "APP_ONLY",
      identity: "SNAPCHAT",
      evidenceSpans: ["only Snapchat app"],
      appliesToText: "the Snapchat app only",
    },
  },
  {
    userPromise: "block short videos in the browser too",
    cluster: "short_form_browser_hint",
    expectedScope: {
      kind: "SHORT_FORM_ANY_HOST",
      identity: "SHORT_FORM_ANY",
      evidenceSpans: ["short videos in the browser"],
      appliesToText: "short videos everywhere",
    },
  },
  {
    userPromise: "chrome ok but porn always blocked, movies too",
    cluster: "host_exempt_combo",
    expectedScope: {
      kind: "HOST_WITH_CATEGORY_EXEMPTIONS",
      identity: "ANY",
      evidenceSpans: ["porn always blocked, movies too"],
      appliesToText: "adult and movie pages in your browser",
    },
  },
]);

// ---------------------------------------------------------------------------

const out = rows.map((r) => JSON.stringify(r)).join("\n") + "\n";
fs.writeFileSync(OUT_PATH, out, "utf8");
console.log(`wrote ${rows.length} rows to ${OUT_PATH}`);
