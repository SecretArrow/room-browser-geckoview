'use strict';

// One agent tool, four permissions and a config knob — the parts of the SDK
// a plugin that works alongside the AI needs. count_words is what the chat's
// agent can call; the lifetime total is what storage is for; the activation
// card reads the chat it landed in, which is what chat.context is for; and
// /compose asks this chat's own AI for a sentence, which is what
// agent.generate is for — the smallest honest example of a plugin whose
// words are written by the model the user already configured.

exports.activate = async (room) => {
  const messages = room.chat.history().length;
  room.ui.card({
    title: 'Word Count is running',
    text:
      'The AI agent in this chat can call its count_words tool. Every word it ' +
      'counts adds to a lifetime total kept in this chat\'s own storage.',
    fields: [['messages in this chat', String(messages)]],
    style: 'info',
  });
};

exports.commands = {
  // The minimal agent.generate consumer: count the words, then ask this
  // chat's own model to write one short sentence about them. Every way it
  // can fail is a card with the failure's own words, because "the model
  // could not be reached" is something the user can act on and an empty
  // chat bubble is not.
  compose: {
    run: async (room, args) => {
      const text = typeof args === 'string' ? args.trim() : '';
      const words = text.split(/\s+/).filter((word) => word.length > 0).length;
      try {
        const sentence = await room.agent.generate({
          purpose: 'word-count demo',
          instruction:
            'Write one short casual sentence that mentions the number ' +
            (words > 0 ? words + ' and what was counted: "' + text + '"' : 'zero, because nothing was counted'),
          style: { language: 'en', maxWords: 16, oneSentence: true, tone: 'casual' },
        });
        room.ui.card({
          title: 'The chat\'s AI composed',
          text: sentence,
          fields: [['words counted', String(words)]],
          style: 'success',
        });
      } catch (err) {
        room.ui.card({
          title: 'The chat\'s AI could not compose',
          text: String(err && err.message ? err.message : err),
          fields: [['words counted', String(words)]],
          style: 'warn',
        });
      }
    },
  },
};

exports.tools = {
  count_words: {
    description: 'Count the words in a text',
    parameters: {
      type: 'object',
      properties: {
        text: {
          type: 'string',
          description: 'The text whose words are counted',
        },
      },
      required: ['text'],
    },
    run: async (room, args) => {
      const text = args && typeof args.text === 'string' ? args.text : '';
      const ignoreCase = room.config.get('ignore_case') !== false;
      const body = ignoreCase ? text.toLowerCase() : text;
      const words = body.split(/\s+/).filter((word) => word.length > 0).length;
      const lifetime = (room.storage.kv.get('lifetime_words') || 0) + words;
      room.storage.kv.set('lifetime_words', lifetime);
      return words + ' words (lifetime total ' + lifetime + ')';
    },
  },
};
