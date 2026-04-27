#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# PerlOnJava bundles a no-op stub of the deprecated `encoding` pragma so
# that older CPAN modules (which still write `use encoding 'utf8';`)
# can be loaded. We don't emulate the source-encoding or chr/ord/length
# overrides; we only honour explicit filehandle-layer arguments.

subtest 'use encoding;  (no args)' => sub {
    my $loaded = eval { require encoding; 1 };
    ok($loaded, 'encoding.pm loads')
        or diag $@;

    # Bare `use encoding;` must not die and must not affect anything.
    eval { encoding->import() };
    is($@, '', 'use encoding; (no args) is a no-op');
};

subtest "use encoding 'utf8'" => sub {
    eval { encoding->import('utf8') };
    is($@, '', "use encoding 'utf8'; does not die");

    # `length` and `ord` must keep their byte-vs-character semantics
    # unchanged - the historical pragma's overrides are intentionally
    # NOT emulated. Without `use utf8;`, the literal "é" is two UTF-8
    # bytes and PerlOnJava correctly reports length 2.
    my $latin1_byte = "\xE9";
    is(length($latin1_byte), 1, 'length of single byte is 1 (not overridden)');
    is(ord($latin1_byte),  233, 'ord of single byte is 233 (not overridden)');
};

subtest "no encoding;" => sub {
    eval { encoding->unimport() };
    is($@, '', 'no encoding; does not die');
};

subtest 'filehandle-layer arguments' => sub {
    # We don't crash and we don't propagate binmode failures.
    eval { encoding->import('utf8', STDOUT => 'utf8') };
    is($@, '', "use encoding 'utf8', STDOUT => 'utf8' does not die");

    eval { encoding->import('utf8', BogusHandle => 'utf8') };
    is($@, '', 'unknown filehandle name does not die');
};

subtest 'encoding::name accessor' => sub {
    is(encoding::name(), 'utf8',
       'encoding::name() always reports utf8 (matches our parser)');
};

subtest 'real-world load form (utf8 + Encode + encoding)' => sub {
    # This is the exact line that appears in Lingua::ZH::MMSEG and a
    # number of other CJK CPAN modules. It must compile cleanly even
    # when combined with `use utf8;` and `use Encode;`.
    my $code = q{
        use strict;
        use warnings;
        use utf8;
        use Encode qw(is_utf8);
        use encoding 'utf8';
        1;
    };
    my $ok = eval $code;
    ok($ok, "use encoding 'utf8'; compiles next to use utf8 / use Encode")
        or diag "compile error: $@";
};

done_testing();
