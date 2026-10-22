// MD5 of a string's UTF-8 bytes, in the style of the hash helpers sites ship (32-bit integer
// arithmetic, rotations, byte packing): written for the JavaScript self-test.
var md5 = (function () {
  var K = [
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391
  ];
  var SHIFT = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];

  function hex(word) {
    var s = '';
    for (var i = 0; i < 4; i++) s += ((word >>> (i * 8)) & 0xff).toString(16).padStart(2, '0');
    return s;
  }

  return function (text) {
    var bytes = unescape(encodeURIComponent(text));
    var n = bytes.length;
    var blocks = ((n + 8) >> 6) + 1;
    var words = new Array(blocks * 16);
    for (var w = 0; w < words.length; w++) words[w] = 0;
    for (var i = 0; i < n; i++) words[i >> 2] |= bytes.charCodeAt(i) << ((i % 4) * 8);
    words[n >> 2] |= 0x80 << ((n % 4) * 8);
    words[blocks * 16 - 2] = (n * 8) | 0;
    words[blocks * 16 - 1] = Math.floor(n / 0x20000000);

    var h0 = 0x67452301, h1 = 0xefcdab89 | 0, h2 = 0x98badcfe | 0, h3 = 0x10325476;
    for (var off = 0; off < words.length; off += 16) {
      var a = h0, b = h1, c = h2, d = h3;
      for (var r = 0; r < 64; r++) {
        var f, g;
        if (r < 16) {
          f = (b & c) | (~b & d);
          g = r;
        } else if (r < 32) {
          f = (d & b) | (~d & c);
          g = (5 * r + 1) % 16;
        } else if (r < 48) {
          f = b ^ c ^ d;
          g = (3 * r + 5) % 16;
        } else {
          f = c ^ (b | ~d);
          g = (7 * r) % 16;
        }
        var sum = (a + f + K[r] + words[off + g]) | 0;
        var s = SHIFT[(r >> 4) * 4 + (r % 4)];
        a = d;
        d = c;
        c = b;
        b = (b + ((sum << s) | (sum >>> (32 - s)))) | 0;
      }
      h0 = (h0 + a) | 0;
      h1 = (h1 + b) | 0;
      h2 = (h2 + c) | 0;
      h3 = (h3 + d) | 0;
    }
    return hex(h0) + hex(h1) + hex(h2) + hex(h3);
  };
})();
