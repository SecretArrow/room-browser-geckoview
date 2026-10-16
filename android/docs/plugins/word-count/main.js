'use strict';

// One agent tool, three permissions and a config knob — the parts of the SDK
// a plugin that works alongside the AI needs. count_words is what the chat's
// agent can call; the lifetime total is what storage is for; the activation
// card reads the chat it landed in, which is what chat.context is for.

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
