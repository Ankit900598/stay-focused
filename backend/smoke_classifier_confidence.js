const assert = require("assert");
const fs = require("fs");
const path = require("path");

process.env.AZURE_OPENAI_ENDPOINT = "https://example.openai.azure.com/";
process.env.AZURE_OPENAI_API_KEY = "local-test-key";
process.env.AZURE_OPENAI_DEPLOYMENT = "pc-lab-terra";
process.env.AZURE_OPENAI_API_VERSION = "2024-08-01-preview";

const routing = require("./azureModelRouting");
const originalCompletion = routing.completeJsonChatWithFallback;
let confidence = 0.8;
let calls = 0;

routing.completeJsonChatWithFallback = async () => {
  calls += 1;
  return {
    deployment: "pc-lab-terra",
    completion: {
      choices: [{ message: { content: JSON.stringify({ decision: "BLOCK", confidence, reasonCategory: "short_form_disallowed", reason: "Test" }) } }],
    },
  };
};

const {
  classifyActivity,
  getTextClassifierCategories,
  getVisionAdvisoryCategories,
  PROMPT_PATH,
} = require("./classifierService");
routing.completeJsonChatWithFallback = originalCompletion;

async function main() {
  const prompt = fs.readFileSync(PROMPT_PATH, "utf8");
  const categorySection = prompt.match(/^reasonCategory:\s*([\s\S]*?)\r?\n\r?\n=== DECISION PHILOSOPHY/m)?.[1];
  assert.ok(categorySection, "classifier prompt declares a reason-category vocabulary");
  const promptCategories = categorySection.split(",").map((category) => category.trim()).filter(Boolean).sort();
  assert.deepStrictEqual(getTextClassifierCategories(), promptCategories);

  const androidParserPath = path.join(
    __dirname,
    "../app/src/main/java/com/phonecodex/app/domain/classifier/NetworkAiResponseParser.kt"
  );
  const androidParser = fs.readFileSync(androidParserPath, "utf8");
  const readKotlinCategories = (name) => {
    const section = androidParser.match(new RegExp(`private val ${name} = setOf\\(([\\s\\S]*?)\\n    \\)`))?.[1];
    assert.ok(section, `Android parser declares ${name}`);
    return [...section.matchAll(/"([^"]+)"/g)].map((match) => match[1]).sort();
  };
  assert.deepStrictEqual(readKotlinCategories("TEXT_REASON_CATEGORIES"), getTextClassifierCategories());
  assert.deepStrictEqual(readKotlinCategories("VISION_REASON_CATEGORIES"), getVisionAdvisoryCategories());

  const body = { packageName: "com.example.video", screenText: "Active player", goal: "No short videos" };
  const valid = await classifyActivity(body);
  assert.strictEqual(valid.decision, "BLOCK");
  assert.strictEqual(valid.confidence, 0.8);

  for (const invalid of [2, -0.01, "0.9", null]) {
    confidence = invalid;
    calls = 0;
    await assert.rejects(classifyActivity(body), /Invalid confidence from model/);
    assert.strictEqual(calls, 1);
  }

  console.log("ASSERTIONS_PASSED=classifier_contract");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
