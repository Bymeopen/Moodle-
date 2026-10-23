import Anthropic from '@anthropic-ai/sdk';

const MODEL = process.env.AI_MODEL || 'claude-opus-5-5';

/**
 * Pembungkus tipis Claude API. Mengembalikan null bila tidak ada kredensial,
 * sehingga fitur AI otomatis nonaktif (endpoint balas 503).
 * Antarmuka: { json({ system, user, schema, maxTokens }), text({ system, user, maxTokens }) }
 */
export function createAi() {
  if (!process.env.ANTHROPIC_API_KEY && !process.env.ANTHROPIC_AUTH_TOKEN) return null;
  const client = new Anthropic();

  async function run({ system, user, schema, maxTokens = 16000 }) {
    const response = await client.beta.messages.create({
      model: MODEL,
      max_tokens: maxTokens,
      system,
      messages: [{ role: 'user', content: user }],
      output_config: { effort: 'medium', ...(schema && { format: { type: 'json_schema', schema } }) },
      // jika model menolak (safety), API otomatis mencoba model cadangan
      betas: ['server-side-fallback-2026-07-01'],
      fallbacks: 'default',
    });
    if (response.stop_reason === 'refusal') throw new Error('Permintaan ditolak oleh model');
    if (response.stop_reason === 'max_tokens') throw new Error('Respons AI terpotong, coba input lebih pendek');
    // content dapat berisi blok thinking; ambil teks saja
    return response.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
  }

  return {
    json: async (opts) => JSON.parse(await run(opts)),
    text: run,
  };
}
