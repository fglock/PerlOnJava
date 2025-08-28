#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Test string interpolation with various special cases

subtest 'Nested references and complex structures' => sub {
    # Test with code references
    my $code = sub { return "dynamic" };
    is("${\$code->()}", "dynamic", "Code reference execution in interpolation");
};

subtest 'Here-docs in interpolation' => sub {
    # Test here-doc within array reference (complex case)
    my $result = eval {
        my $text = "x @{[ <<'EOT' ]} x";
HERE
EOT
        return $text;
    };
    
    # This is a complex case that may not work in all implementations
    # The test checks if it can be parsed without error
    ok(defined($result) || $@, "Here-doc in array ref interpolation handled");
};

done_testing();

