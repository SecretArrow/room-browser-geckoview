'use strict';

// The smallest honest plugin: an activation card, one /greet command, and a
// counter that survives between calls in this chat's own kv store. The
// greeting word itself is config, so the same plugin answers in any voice the
// chat's owner prefers.

exports.activate = async (room) => {
  room.ui.card({
    title: room.meta.pluginName + ' is running',
    text:
      'Type /greet <name> in this chat and it answers with a card; every greeting ' +
      'is counted in this chat\'s own storage.',
    style: 'info',
  });
};

exports.commands = {
  greet: {
    run: async (room, args) => {
      const who = (args || '').trim() || 'world';
      const greeting = room.config.get('greeting') || 'Hello';
      const count = (room.storage.kv.get('greetings') || 0) + 1;
      room.storage.kv.set('greetings', count);
      return {
        title: greeting + ', ' + who + '!',
        text: 'greeting #' + count + ' in this chat',
        style: 'success',
        fields: [['plugin', room.meta.pluginId], ['greetings so far', String(count)]],
      };
    },
  },
};
