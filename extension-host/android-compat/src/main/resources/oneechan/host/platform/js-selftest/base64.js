// atob/btoa as extension scripts polyfill them (QuickJS has neither): binary strings in and out,
// and the decodeURIComponent(escape(...)) idiom for UTF-8 text. Written for the JavaScript self-test.
var Base64 = (function () {
  var ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var lookup = {};
  for (var i = 0; i < ALPHABET.length; i++) lookup[ALPHABET.charAt(i)] = i;

  function atob(input) {
    var clean = String(input).replace(/[\s=]+/g, '');
    if (clean.length % 4 === 1 || /[^A-Za-z0-9+/]/.test(clean)) throw new Error('Invalid base64');
    var out = [], buffer = 0, bits = 0;
    for (var i = 0; i < clean.length; i++) {
      buffer = (buffer << 6) | lookup[clean.charAt(i)];
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out.push(String.fromCharCode((buffer >> bits) & 0xff));
      }
    }
    return out.join('');
  }

  function btoa(binary) {
    var out = '', i = 0;
    for (; i + 2 < binary.length; i += 3) {
      var n = (binary.charCodeAt(i) << 16) | (binary.charCodeAt(i + 1) << 8) | binary.charCodeAt(i + 2);
      out += ALPHABET.charAt(n >> 18) + ALPHABET.charAt((n >> 12) & 63) + ALPHABET.charAt((n >> 6) & 63) + ALPHABET.charAt(n & 63);
    }
    var rest = binary.length - i;
    if (rest === 1) {
      var one = binary.charCodeAt(i) << 16;
      out += ALPHABET.charAt(one >> 18) + ALPHABET.charAt((one >> 12) & 63) + '==';
    } else if (rest === 2) {
      var two = (binary.charCodeAt(i) << 16) | (binary.charCodeAt(i + 1) << 8);
      out += ALPHABET.charAt(two >> 18) + ALPHABET.charAt((two >> 12) & 63) + ALPHABET.charAt((two >> 6) & 63) + '=';
    }
    return out;
  }

  return {
    atob: atob,
    btoa: btoa,
    decodeText: function (data) { return decodeURIComponent(escape(atob(data))); },
    encodeText: function (text) { return btoa(unescape(encodeURIComponent(text))); },
  };
})();
