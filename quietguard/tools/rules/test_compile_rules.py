import unittest
import compile_rules as c

class CompilerChecks(unittest.TestCase):
    def test_formats_and_deduplication(self):
        data, stats = c.compile_text('! title\n||ADS.Example.com^\nads.example.com\n0.0.0.0 a.example.com b.example.com # hosts\n@@||safe.ads.example.com^\n')
        self.assertEqual(data, b'!safe.ads.example.com\na.example.com\nads.example.com\nb.example.com\n')
        self.assertEqual(stats['entries'],4)
    def test_scoped_block_is_never_broadened(self):
        data, stats=c.compile_text('||ad.example.com^\n||example.com^$script\nexample.net/path\n')
        self.assertEqual(data,b'ad.example.com\n')
        self.assertEqual(stats['skippedBlockingLines'],2)
    def test_scoped_exception_rejects_whole_source(self):
        with self.assertRaises(ValueError):
            c.compile_text('||example.com^\n@@||safe.example.com^$client=phone\n')
    def test_invalid_domains(self):
        for value in ['127.0.0.1','::1','https://example.com','co*m.example.com','com','a..com','-a.example.com','a.'+'x'*64+'.com','例子.com']:
            with self.subTest(value=value), self.assertRaises(ValueError): c.domain(value)
    def test_empty_rejected(self):
        with self.assertRaises(ValueError): c.compile_text('! empty\n@@||safe.example.com^')
    def test_manifest_is_deterministic_and_binds_sources(self):
        a=c.manifest(1,17,b'a.example.com\n',b'{}\n',1)
        self.assertEqual(a,c.manifest(1,17,b'a.example.com\n',b'{}\n',1))
        self.assertNotEqual(a,c.manifest(1,17,b'a.example.com\n',b'{"a":1}\n',1))

if __name__=='__main__': unittest.main()
