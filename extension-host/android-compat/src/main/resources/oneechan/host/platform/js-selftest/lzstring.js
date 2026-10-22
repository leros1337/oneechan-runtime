// An LZString-compatible codec (the "LZString.compressToBase64" format sites use for page
// lists), written for the JavaScript self-test: an LZW variant whose codes grow from 2 bits,
// packed least significant bit first into 6-bit base64 digits.
var LZ = (function () {
  var DIGITS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  var digitValue = {};
  for (var i = 0; i < DIGITS.length; i++) digitValue[DIGITS.charAt(i)] = i;

  function BitWriter(width, digit) {
    this.width = width;
    this.digit = digit;
    this.out = [];
    this.value = 0;
    this.filled = 0;
  }
  BitWriter.prototype.write = function (code, bits) {
    for (var n = 0; n < bits; n++) {
      this.value = (this.value << 1) | (code & 1);
      code >>= 1;
      if (++this.filled === this.width) {
        this.out.push(this.digit(this.value));
        this.value = 0;
        this.filled = 0;
      }
    }
  };
  BitWriter.prototype.finish = function () {
    while (true) {
      this.value <<= 1;
      if (++this.filled === this.width) {
        this.out.push(this.digit(this.value));
        return this.out.join('');
      }
    }
  };

  function compress(text, width, digit) {
    if (text == null) return '';
    var codes = Object.create(null), fresh = Object.create(null);
    var next = 3, bits = 2, budget = 2, w = '';
    var out = new BitWriter(width, digit);
    function grow() {
      if (--budget === 0) {
        budget = 1 << bits;
        bits++;
      }
    }
    function emit(phrase) {
      if (phrase in fresh) {
        var code = phrase.charCodeAt(0);
        if (code < 256) {
          out.write(0, bits);
          out.write(code, 8);
        } else {
          out.write(1, bits);
          out.write(code, 16);
        }
        grow();
        delete fresh[phrase];
      } else {
        out.write(codes[phrase], bits);
      }
      grow();
    }
    for (var i = 0; i < text.length; i++) {
      var c = text.charAt(i);
      if (!(c in codes)) {
        codes[c] = next++;
        fresh[c] = true;
      }
      var wc = w + c;
      if (wc in codes) {
        w = wc;
      } else {
        emit(w);
        codes[wc] = next++;
        w = c;
      }
    }
    if (w !== '') emit(w);
    out.write(2, bits);
    return out.finish();
  }

  function decompress(length, width, digitAt) {
    var dict = ['', '', ''], next = 4, bits = 3, budget = 4;
    var value = digitAt(0), mask = width, index = 1;
    function read(count) {
      var result = 0;
      for (var n = 0; n < count; n++) {
        var bit = value & mask;
        mask >>= 1;
        if (mask === 0) {
          mask = width;
          value = digitAt(index++);
        }
        if (bit) result |= 1 << n;
      }
      return result;
    }
    var first;
    switch (read(2)) {
      case 0: first = String.fromCharCode(read(8)); break;
      case 1: first = String.fromCharCode(read(16)); break;
      default: return '';
    }
    dict[3] = first;
    var w = first, parts = [first];
    while (true) {
      if (index > length) return '';
      var code = read(bits);
      if (code === 0 || code === 1) {
        dict[next++] = String.fromCharCode(read(code === 0 ? 8 : 16));
        code = next - 1;
        if (--budget === 0) {
          budget = 1 << bits;
          bits++;
        }
      } else if (code === 2) {
        return parts.join('');
      }
      var entry;
      if (code < next && dict[code] !== undefined) entry = dict[code];
      else if (code === next) entry = w + w.charAt(0);
      else return null;
      parts.push(entry);
      dict[next++] = w + entry.charAt(0);
      w = entry;
      if (--budget === 0) {
        budget = 1 << bits;
        bits++;
      }
    }
  }

  return {
    compressToBase64: function (text) {
      var packed = compress(text, 6, function (v) { return DIGITS.charAt(v); });
      return packed + '==='.substring(0, (4 - (packed.length % 4)) % 4);
    },
    decompressFromBase64: function (data) {
      if (data == null) return '';
      if (data === '') return null;
      return decompress(data.length, 32, function (i) { return digitValue[data.charAt(i)]; });
    },
  };
})();
