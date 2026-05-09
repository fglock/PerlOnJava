#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Start with utf8 off by default

subtest 'Default utf8 off' => sub {
    # ASCII range (0-127)
    my $ascii = "Hello";
    is(length($ascii), 5, 'ASCII string length');
    
    # Latin-1 range (128-255)
    my $latin1 = "\xE9";  # é in Latin-1
    is(length($latin1), 1, 'Latin-1 character is 1 octet');
    is(ord($latin1), 233, 'Latin-1 character value is 233');
    
    # String literal with non-ASCII (UTF-8 bytes in source)
    my $str = "café";  # Without utf8, each UTF-8 byte is counted
    is(length($str), 5, 'café is 5 octets without utf8');
};

subtest 'utf8 pragma enabled' => sub {
    use utf8;
    
    # ASCII range still works the same
    my $ascii = "Hello";
    is(length($ascii), 5, 'ASCII string length with utf8');
    
    # String literal with non-ASCII
    my $str = "café";  # With utf8, characters are counted
    is(length($str), 4, 'café is 4 characters with utf8');
    
    # Unicode beyond Latin-1
    my $unicode = "Ā";  # U+0100
    is(length($unicode), 1, 'Unicode character is 1 character');
    is(ord($unicode), 256, 'Unicode character value is 256');
    
    # Multiple Unicode characters
    my $multi = "café™";
    is(length($multi), 5, 'Mixed Unicode string length');
    is(ord(substr($multi, 4, 1)), 8482, 'TM symbol is U+2122');
};

subtest 'Lexical scope of utf8' => sub {
    # Start without utf8
    my $outer = "café";
    is(length($outer), 5, 'Outer scope: café is 5 octets');
    
    {
        use utf8;
        my $inner = "café";
        is(length($inner), 4, 'Inner scope with utf8: café is 4 characters');
        
        # Check that outer variable is still octets
        is(length($outer), 5, 'Outer variable unchanged in inner scope');
    }
    
    # Back to no utf8
    my $after = "café";
    is(length($after), 5, 'After inner scope: café is 5 octets again');
};

subtest 'no utf8 inside utf8 block' => sub {
    use utf8;
    
    my $with_utf8 = "café";
    is(length($with_utf8), 4, 'With utf8: café is 4 characters');
    
    {
        no utf8;
        my $without_utf8 = "café";
        is(length($without_utf8), 5, 'With no utf8: café is 5 octets');
    }
    
    my $still_utf8 = "café";
    is(length($still_utf8), 4, 'After no utf8 block: café is 4 characters again');
};

subtest 'Different character ranges' => sub {
    use utf8;
    
    # Test various Unicode ranges
    my $ascii = "A";           # U+0041
    my $latin1 = "é";          # U+00E9
    my $latin_ext = "Ā";       # U+0100
    my $cyrillic = "Я";        # U+042F
    my $cjk = "中";            # U+4E2D
    my $emoji = "😀";          # U+1F600
    
    is(length($ascii), 1, 'ASCII character length');
    is(length($latin1), 1, 'Latin-1 character length');
    is(length($latin_ext), 1, 'Latin Extended character length');
    is(length($cyrillic), 1, 'Cyrillic character length');
    is(length($cjk), 1, 'CJK character length');

    # TODO
    # is(length($emoji), 1, 'Emoji character length');
    
    is(ord($ascii), 65, 'ASCII character value');
    is(ord($latin1), 233, 'Latin-1 character value');
    is(ord($latin_ext), 256, 'Latin Extended character value');
    is(ord($cyrillic), 1071, 'Cyrillic character value');
    is(ord($cjk), 20013, 'CJK character value');

    # TODO
    # is(ord($emoji), 128512, 'Emoji character value');
};

subtest 'Operators work normally' => sub {
    # Without utf8 - using Latin-1 bytes
    {
        no utf8;
        my $str1 = "caf\xE9";  # Latin-1 encoding
        my $str2 = "caf\xE9";
        
        ok($str1 eq $str2, 'String equality works without utf8');
        is($str1 . "!", "caf\xE9!", 'String concatenation works without utf8');
        
        # Test uc() by checking ord values
        my $upper = uc($str1);
        is(length($upper), 4, 'uc() result has correct length');
        is(ord(substr($upper, 0, 1)), ord('C'), 'First char uppercased correctly');
        is(ord(substr($upper, 1, 1)), ord('A'), 'Second char uppercased correctly');
        is(ord(substr($upper, 2, 1)), ord('F'), 'Third char uppercased correctly');

        # TODO
        # is(ord(substr($upper, 3, 1)), 233, 'Latin-1 é (233) unchanged by uc() in octet mode');
    }
    
    # With utf8
    {
        use utf8;
        my $str1 = "café";
        my $str2 = "café";
        
        ok($str1 eq $str2, 'String equality works with utf8');
        is($str1 . "!", "café!", 'String concatenation works with utf8');
        is(uc($str1), "CAFÉ", 'uc() works on characters');
        
        # Unicode-specific operations
        my $unicode = "Ā";
        is(lc($unicode), "ā", 'lc() works on Unicode');
    }
};

subtest 'Wide character detection' => sub {
    use utf8;
    
    # This tests if wide characters are properly handled
    my $wide = "中文";
    is(length($wide), 2, 'Wide characters counted correctly');
    
    # Test string with mixed content
    my $mixed = "Hello 世界";
    is(length($mixed), 8, 'Mixed ASCII and wide characters');
    
    # Character extraction
    is(substr($mixed, 6, 1), "世", 'Extract wide character');
    is(ord(substr($mixed, 6, 1)), 19990, 'Wide character ord value');
};

subtest 'Escape sequences vs source encoding' => sub {
    # Without utf8 - escape sequences still create Unicode strings
    {
        no utf8;
        my $hex = "\x{100}";  # Creates Unicode string
        is(length($hex), 1, 'Unicode escape creates single character even without utf8');
        is(ord($hex), 256, 'Unicode escape value without utf8');
        
        # But literal source is treated as bytes
        my $literal = "Ā";  # This is UTF-8 bytes in source
        is(length($literal), 2, 'Literal Ā is 2 bytes without utf8');
    }
    
    # With utf8
    {
        use utf8;
        my $hex = "\x{100}";  # Creates Unicode string
        is(length($hex), 1, 'Unicode escape creates single character with utf8');
        is(ord($hex), 256, 'Unicode escape value with utf8');

        my $byte_escape = "B\xFCcher";
        ok(!utf8::is_utf8($byte_escape), 'fixed byte escape is not UTF-8 flagged under utf8');
        is(lc("\xC3\xBCri"), "\xC3\xBCri", 'lc preserves non-ASCII bytes from fixed byte escapes');

        # Literal source is treated as characters
        my $literal = "Ā";
        is(length($literal), 1, 'Literal Ā is 1 character with utf8');
        is(ord($literal), 256, 'Literal Ā has correct value with utf8');

        my $latin1_literal = "ü";
        ok(utf8::is_utf8($latin1_literal), 'literal non-ASCII source is UTF-8 flagged under utf8');
    }
};

subtest 'utf8 only affects source code literals' => sub {
    # Test that utf8 pragma only affects how source code is interpreted
    
    # Without utf8
    {
        no utf8;
        # These are always Unicode, regardless of utf8 pragma
        my $chr = chr(256);
        is(ord($chr), 256, 'chr() creates Unicode regardless of utf8');
        
        my $pack = pack("U", 256);
        is(ord($pack), 256, 'pack("U") creates Unicode regardless of utf8');
        
        # But source literals are bytes
        my $literal = "Ā";
        ok(length($literal) > 1, 'Source literal is bytes without utf8');
    }
    
    # With utf8
    {
        use utf8;
        my $chr = chr(256);
        is(ord($chr), 256, 'chr() creates Unicode with utf8');
        
        my $pack = pack("U", 256);
        is(ord($pack), 256, 'pack("U") creates Unicode with utf8');
        
        # Source literals are characters
        my $literal = "Ā";
        is(length($literal), 1, 'Source literal is character with utf8');
    }
};

subtest 'Octet vs character semantics' => sub {
    # Test the difference between octet and character operations
    
    # Without utf8 - octet semantics
    {
        no utf8;
        my $octets = "caf\xE9";  # 4 octets: c(99) a(97) f(102) é(233)
        
        # Test length
        is(length($octets), 4, 'Length counts octets');
        
        # Test substr with octets
        is(ord(substr($octets, 3, 1)), 233, 'substr works on octets');
        
        # Test character class matching
        my $count = () = $octets =~ /./g;
        is($count, 4, 'Regex . matches octets');
    }
    
    # With utf8 - character semantics
    {
        use utf8;
        my $chars = "café";  # 4 characters
        
        # Test length
        is(length($chars), 4, 'Length counts characters');
        
        # Test substr with characters
        is(substr($chars, 3, 1), "é", 'substr works on characters');
        
        # Test character class matching
        my $count = () = $chars =~ /./g;
        is($count, 4, 'Regex . matches characters');
    }
};

done_testing();
