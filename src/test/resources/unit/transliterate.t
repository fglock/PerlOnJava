use strict;
use warnings;
use Test::More;

subtest 'Basic transliteration' => sub {
    # Simple transliteration
    my $string = "Hello World";
    my $trans = $string =~ tr/o/O/;
    is($string, "HellO WOrld", "'Hello World' transliterates 'o' to 'O'");

    # Multiple character transliteration
    $string = "Hello World";
    $trans = $string =~ tr/lo/LO/;
    is($string, "HeLLO WOrLd", "'Hello World' transliterates 'l' to 'L' and 'o' to 'O'");

    # Transliteration with character range
    $string = "abcdef";
    $trans = $string =~ tr/a-f/A-F/;
    is($string, "ABCDEF", "'abcdef' transliterates 'a-f' to 'A-F'");
};

subtest 'Transliteration with modifiers' => sub {
    # Transliteration with deletion
    my $string = "Hello World";
    my $trans = $string =~ tr/o//d;
    is($string, "Hell Wrld", "'Hello World' deletes 'o' characters");

    # Transliteration with complement
    $string = "Hello World";
    $trans = $string =~ tr/a-zA-Z/-/c;
    is($string, "Hello-World", "'Hello World' changes all non-alphabetic characters");

    # Transliteration with squeeze
    $string = "Hellooo   World";
    $trans = $string =~ tr/o/0/s;
    is($string, "Hell0   W0rld", "'Hellooo   World' squeezes 'o' characters to '0'");
};

subtest 'Transliteration with combined modifiers' => sub {
    # Transliteration with complement and squeeze
    my $string = "Hellooo   World";
    my $trans = $string =~ tr/a-zA-Z/-/cs;
    is($string, "Hellooo-World", "'Hellooo   World' replaces non-alphabetic characters and squeezes");

    # Transliteration with range and deletion
    $string = "abcdef";
    $trans = $string =~ tr/a-f/A-F/d;
    is($string, "ABCDEF", "'abcdef' transliterates 'a-f' to 'A-F' with deletion flag");

    # Transliteration with range and complement
    $string = "abcdef";
    $trans = $string =~ tr/-a-f/-A-F/c;
    is($string, "abcdef", "'abcdef' does not change as complement of 'a-f' is empty");

    # Transliteration with range, complement, and deletion
    $string = "abcdef";
    $trans = $string =~ tr/a-f/A-F/cd;
    is($string, "abcdef", "'abcdef' with complement and deletion flags");
};

subtest 'Transliteration with complement on mixed strings' => sub {
    # Transliteration with complement
    my $string = "Hello World 123!";
    my $trans = $string =~ tr/a-zA-Z/X/c;
    is($string, "HelloXWorldXXXXX", "'Hello World 123!' replaces non-alphabetic characters with 'X'");

    # Transliteration with complement and deletion
    $string = "Hello World 123!";
    $trans = $string =~ tr/a-zA-Z//cd;
    is($string, "HelloWorld", "'Hello World 123!' deletes non-alphabetic characters");

    # Transliteration with complement and squeeze
    $string = "Hello World 123!";
    $trans = $string =~ tr/a-zA-Z/X/cs;
    is($string, "HelloXWorldX", "'Hello World 123!' replaces non-alphabetic characters with 'X' and squeezes");
};

subtest 'Transliteration with octal ranges and complement (BUG)' => sub {
    # Test for the suspected bug with tr///c and octal ranges
    my $string;
    my $count;

    # Test 1: ASCII character 'A' should be within \0-\377 range
    $string = "A";
    $count = $string =~ tr/\0-\377//c;
    TODO: {
        local $TODO = "tr///c with octal ranges incorrectly counts all chars as outside range";
        is($count, 0, "Character 'A' should be within \\0-\\377 range");
    }

    # Test 2: Multiple ASCII characters should all be within \0-\377 range
    $string = "ABCDE";
    $count = $string =~ tr/\0-\377//c;
    TODO: {
        local $TODO = "tr///c with octal ranges incorrectly counts all chars as outside range";
        is($count, 0, "All characters in 'ABCDE' should be within \\0-\\377 range");
    }

    # Test 3: Extended ASCII characters should be within \0-\377 range
    $string = chr(161) . chr(162) . chr(163);
    $count = $string =~ tr/\0-\377//c;
    TODO: {
        local $TODO = "tr///c with octal ranges incorrectly counts all chars as outside range";
        is($count, 0, "Characters chr(161-163) should be within \\0-\\377 range");
    }

    # Test 4: Character above 255 should be outside \0-\377 range
    $string = chr(256);
    $count = $string =~ tr/\0-\377//c;
    is($count, 1, "Character chr(256) should be outside \\0-\\377 range");

    # Test 5: Mixed string with 8-bit and non-8-bit characters
    $string = "ABC" . chr(256) . "DEF";
    $count = $string =~ tr/\0-\377//c;
    TODO: {
        local $TODO = "tr///c with octal ranges incorrectly counts all chars as outside range";
        is($count, 1, "Only chr(256) should be outside \\0-\\377 range in mixed string");
    }

    # Test 6: Test with smaller range \0-\177 (0-127)
    $string = "A";
    $count = $string =~ tr/\0-\177//c;
    TODO: {
        local $TODO = "tr///c with character ranges not working correctly";
        is($count, 0, "Character 'A' (65) should be within \\0-\\177 range");
    }

    # Test 7: Character above 127 should be outside \0-\177 range
    $string = chr(128);
    $count = $string =~ tr/\0-\177//c;
    is($count, 1, "Character chr(128) should be outside \\0-\\177 range");
};

done_testing();
