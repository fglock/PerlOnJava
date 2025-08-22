use strict;
use warnings;
use Test::More;

# Test class with qr overloading that returns a compiled regex
{
    package RegexHolder;
    use overload
        'qr' => \&as_regex,
        '""' => \&as_string;

    sub new {
        my ($class, $pattern) = @_;
        return bless { pattern => $pattern }, $class;
    }

    sub as_regex {
        my $self = shift;
        return qr/$self->{pattern}/;
    }

    sub as_string {
        my $self = shift;
        return "RegexHolder($self->{pattern})";
    }
}

# Test class with qr overload that includes flags
{
    package RegexWithFlags;
    use overload 'qr' => \&as_regex;

    sub new {
        my ($class, $pattern, $flags) = @_;
        return bless { pattern => $pattern, flags => $flags || '' }, $class;
    }

    sub as_regex {
        my $self = shift;
        return $self->{flags} ? qr/(?$self->{flags}:$self->{pattern})/ : qr/$self->{pattern}/;
    }
}

# Test class with fallback to string conversion
{
    package StringOnlyRegex;
    use overload
        '""' => \&as_string,
        fallback => 1;

    sub new {
        my ($class, $pattern) = @_;
        return bless { pattern => $pattern }, $class;
    }

    sub as_string {
        my $self = shift;
        return $self->{pattern};
    }
}

# Test class that incorrectly returns a non-regex
{
    package BadRegexOverload;
    use overload 'qr' => \&bad_regex;

    sub new {
        my $class = shift;
        return bless {}, $class;
    }

    sub bad_regex {
        return "not a regex";
    }
}

subtest 'Basic qr overloading' => sub {
    my $regex_obj = RegexHolder->new('test');

    ok("test string" =~ $regex_obj, "Object with qr overload matches correctly");
    ok(!("no match" =~ $regex_obj), "Object with qr overload fails to match correctly");

    # Test with captures
    my $capture_obj = RegexHolder->new('(\w+)\s+(\w+)');
    ok("hello world" =~ $capture_obj, "Regex with captures matches");
    is($1, "hello", "First capture group works");
    is($2, "world", "Second capture group works");
};

subtest 'qr overload with flags' => sub {
    my $case_obj = RegexWithFlags->new('HELLO', 'i');

    ok("hello" =~ $case_obj, "Case-insensitive regex works with lowercase");
    ok("HELLO" =~ $case_obj, "Case-insensitive regex works with uppercase");
    ok("HeLLo" =~ $case_obj, "Case-insensitive regex works with mixed case");

    my $multiline_obj = RegexWithFlags->new('^test', 'm');
    ok("line1\ntest line2" =~ $multiline_obj, "Multiline flag works");
};

subtest 'Fallback to string conversion' => sub {
    my $string_obj = StringOnlyRegex->new('pattern');

    ok("test pattern here" =~ $string_obj, "Object without qr overload uses string conversion");
    ok(!("no match" =~ $string_obj), "String conversion fails to match correctly");
};

subtest 'qr overload in substitution' => sub {
    my $regex_obj = RegexHolder->new('old');
    my $text = "old text with old words";

    $text =~ s/$regex_obj/new/g;
    is($text, "new text with new words", "Substitution with qr overloaded object works");

    # Test with captures in substitution
    my $capture_obj = RegexHolder->new('(\w+)@(\w+)');
    my $email = "user\@domain";
    $email =~ s/$capture_obj/$2 at $1/;
    is($email, "domain at user", "Substitution with captures works");
};

subtest 'qr overload with !~ operator' => sub {
    my $regex_obj = RegexHolder->new('test');

    ok(!("test string" !~ $regex_obj), "!~ operator with matching pattern returns false");
    ok("no match" !~ $regex_obj, "!~ operator with non-matching pattern returns true");
};

subtest 'qr overload in list context' => sub {
    my $regex_obj = RegexHolder->new('(\w+)\s+(\w+)\s+(\w+)');
    my @matches = "one two three" =~ $regex_obj;

    is_deeply(\@matches, ['one', 'two', 'three'], "List context returns all captures");
};

subtest 'qr overload with match variables' => sub {
    my $regex_obj = RegexHolder->new('(he)(ll)(o)');
    "hello world" =~ $regex_obj;

    is($&, "hello", '$& contains full match');
    is($`, "", '$` contains pre-match');
    is($', " world", '$\' contains post-match');
    is($+, "o", '$+ contains last capture');
};

subtest 'Multiple objects with qr overload' => sub {
    my $obj1 = RegexHolder->new('foo');
    my $obj2 = RegexHolder->new('bar');

    ok("foo" =~ $obj1, "First object matches correctly");
    ok(!("foo" =~ $obj2), "Second object doesn't match first pattern");
    ok("bar" =~ $obj2, "Second object matches its own pattern");
};

subtest 'qr overload with global flag' => sub {
    my $regex_obj = RegexHolder->new('\d+');
    my $text = "123 456 789";

    my @numbers;
    while ($text =~ /$regex_obj/g) {
        push @numbers, $&;
    }

    is_deeply(\@numbers, ['123', '456', '789'], "Global matching works with qr overload");
};

subtest 'Error handling' => sub {
    my $bad_obj = BadRegexOverload->new();

    eval { "test" =~ $bad_obj };
    like($@, qr/Overloaded qr did not return a REGEXP/, "Invalid qr overload return value throws error");
};

subtest 'qr overload with split' => sub {
    my $regex_obj = RegexHolder->new('\s+');
    my @parts = split /$regex_obj/, "one two  three   four";

    is_deeply(\@parts, ['one', 'two', 'three', 'four'], "split works with qr overloaded object");
};

subtest 'Stringification vs qr overload' => sub {
    my $obj = RegexHolder->new('test');

    # String context should use "" overload
    is("$obj", "RegexHolder(test)", "String interpolation uses \"\" overload");

    # Regex context should use qr overload
    ok("test" =~ $obj, "Regex context uses qr overload");

    # Concatenation should use "" overload
    is("prefix " . $obj, "prefix RegexHolder(test)", "Concatenation uses \"\" overload");
};

subtest 'qr overload inheritance' => sub {
    {
        package DerivedRegex;
        use base 'RegexHolder';
        use overload 'qr' => \&as_regex;  # Added: explicitly set overload in derived class

        # Override the pattern transformation
        sub as_regex {
            my $self = shift;
            my $pattern = $self->{pattern};
            return qr/\b$pattern\b/;  # Add word boundaries
        }
    }

    my $derived = DerivedRegex->new('test');

    ok("test" =~ $derived, "Derived class matches whole word");
    ok(!("testing" =~ $derived), "Derived class doesn't match partial word");
    ok("a test here" =~ $derived, "Derived class matches word in sentence");
};

subtest 'qr overload with anchors' => sub {
    my $start_obj = RegexHolder->new('^start');
    my $end_obj = RegexHolder->new('end$');

    ok("start of line" =~ $start_obj, "Start anchor works");
    ok(!("not start" =~ $start_obj), "Start anchor fails correctly");

    ok("at the end" =~ $end_obj, "End anchor works");
    ok(!("end not here" =~ $end_obj), "End anchor fails correctly");
};

subtest 'qr overload with special regex features' => sub {
    # Test with look-ahead
    my $lookahead = RegexHolder->new('foo(?=bar)');
    ok("foobar" =~ $lookahead, "Positive lookahead works");
    ok(!("foobaz" =~ $lookahead), "Positive lookahead fails correctly");

    # Test with non-capturing groups
    my $noncap = RegexHolder->new('(?:foo|bar)(\w+)');
    "fooTest" =~ $noncap;
    is($1, "Test", "Non-capturing group doesn't affect numbering");
};

subtest 'qr overload with pos()' => sub {
    my $regex_obj = RegexHolder->new('\w+');
    my $text = "one two three";

    # First match
    $text =~ /$regex_obj/g;
    is($&, "one", "First match is correct");
    my $pos1 = pos($text);
    ok($pos1 > 0, "pos() is set after match");

    # Second match continues from pos
    $text =~ /$regex_obj/g;
    is($&, "two", "Second match continues from pos");
    ok(pos($text) > $pos1, "pos() advances");
};

subtest 'qr overload with scalar vs list context' => sub {
    my $regex_obj = RegexHolder->new('(\w)(\w)');

    # Scalar context
    my $result = "ab" =~ $regex_obj;
    ok($result, "Scalar context returns true for match");

    # List context
    my @captures = "ab" =~ $regex_obj;
    is_deeply(\@captures, ['a', 'b'], "List context returns captures");
};

subtest 'qr overload with undef and empty patterns' => sub {
    my $empty_obj = RegexHolder->new('');
    ok("anything" =~ $empty_obj, "Empty pattern matches anything");

    my $space_obj = RegexHolder->new(' ');
    ok("has space" =~ $space_obj, "Space pattern works");
    ok(!("nospace" =~ $space_obj), "Space pattern fails without space");
};

subtest 'Complex substitution with qr overload' => sub {
    # Test with /e flag equivalent
    my $regex_obj = RegexHolder->new('(\d+)');
    my $text = "10 20 30";

    # Multiple substitutions
    my $count = $text =~ s/$regex_obj/[$1]/g;
    is($text, "[10] [20] [30]", "Multiple substitutions work");
    is($count, 3, "Substitution count is correct");

    # Non-destructive substitution
    my $original = "test 123";
    my $modified = $original =~ s/$regex_obj/XXX/r;
    is($original, "test 123", "Original unchanged in non-destructive substitution");
    is($modified, "test XXX", "Modified version correct");
};

subtest 'qr overload with blessed references' => sub {
    my $regex_obj = RegexHolder->new('test');

    # Verify the object is blessed
    ok(ref($regex_obj), "Object is a reference");
    isa_ok($regex_obj, 'RegexHolder', "Object has correct class");

    # Still works as regex
    ok("test" =~ $regex_obj, "Blessed object still works as regex");
};

subtest 'qr overload chaining' => sub {
    # Test that qr overload result is not further overloaded
    {
        package DoubleRegex;
        use overload 'qr' => \&as_regex;

        sub new {
            my ($class, $obj) = @_;
            return bless { obj => qr/$obj/ }, $class;
        }

        sub as_regex {
            my $self = shift;
            # Return the inner object's regex
            return $self->{obj};
        }
    }

    my $inner = RegexHolder->new('test');
    my $outer = DoubleRegex->new($inner);

    ok("test" =~ $outer, "Chained qr overload works");
    ok(!("fail" =~ $outer), "Chained qr overload fails correctly");
};

subtest 'qr overload with different return types' => sub {
    # Test returning actual qr// object
    my $qr_obj = RegexHolder->new('direct');
    ok("direct match" =~ $qr_obj, "Direct qr// return works");

    # Test with interpolated variables in pattern
    {
        package InterpolatedRegex;
        use overload 'qr' => \&as_regex;

        sub new {
            my ($class, $prefix) = @_;
            return bless { prefix => $prefix }, $class;
        }

        sub as_regex {
            my $self = shift;
            my $prefix = $self->{prefix};
            return qr/${prefix}\w+/;
        }
    }

    my $interp = InterpolatedRegex->new('test');
    ok("testing" =~ $interp, "Interpolated pattern works");
    ok(!("nottest" =~ $interp), "Interpolated pattern fails correctly");
};

subtest 'qr overload edge cases' => sub {
    # Test with very long patterns
    my $long_pattern = 'a' x 100;
    my $long_obj = RegexHolder->new($long_pattern);
    ok(('a' x 100) =~ $long_obj, "Long pattern matches");
    ok(!(('a' x 99) =~ $long_obj), "Shorter string doesn't match long pattern");

    # Test with special characters
    my $special_obj = RegexHolder->new('test\$\^\\\\');
    ok('test$^\\' =~ $special_obj, "Special characters in pattern work");

    # Test with Unicode (if supported)
    my $unicode_obj = RegexHolder->new('café');
    ok("café" =~ $unicode_obj, "Unicode pattern matches");
};

done_testing();
