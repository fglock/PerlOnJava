use strict;
use warnings;
use Test::More;

sub compile_result {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $rx = eval "qr/$source/";
    return ($rx, $@, join '', @warnings);
}

sub compile_error {
    my ($source) = @_;
    my (undef, $error) = compile_result($source);
    return $error;
}

ok('A' =~ /[[:alpha:]]/, 'named POSIX class remains valid');
ok('7' !~ /[[:alpha:]]/, 'named POSIX class retains membership');

like(
    compile_error('[[=foo=]]'),
    qr/POSIX syntax \[= =\] is reserved for future extensions/,
    'equivalence-class POSIX syntax is reserved',
);
like(
    compile_error('[[=foo=]]'),
    qr/marked by.*\[\[=foo=\]/s,
    'reserved equivalence diagnostic identifies its source',
);
like(
    compile_error('[[.foo.]]'),
    qr/POSIX syntax \[\. \.\] is reserved for future extensions/,
    'collating-element POSIX syntax is reserved',
);
like(
    compile_error('[[.foo.]]'),
    qr/marked by.*\[\[\.foo\.\]/s,
    'reserved collating diagnostic identifies its source',
);
like(
    compile_error('[[=alpha=]]'),
    qr/POSIX syntax \[= =\] is reserved for future extensions/,
    'reserved equivalence syntax is independent of the name',
);
like(
    compile_error('[[.alpha.]]'),
    qr/POSIX syntax \[\. \.\] is reserved for future extensions/,
    'reserved collating syntax is independent of the name',
);

like(
    compile_error('[[:barf:]]'),
    qr/POSIX class \[:barf:\] unknown/,
    'unknown named POSIX class remains distinct from reserved syntax',
);

for my $literal ('[[=]]', '[[=fo\\=o=]]', '[[.fo\\.o.]]') {
    my ($rx, $error, $warnings) = compile_result($literal);
    ok(defined($rx), "$literal remains ordinary class syntax");
    is($error, '', "$literal has no fatal POSIX diagnostic");
    is($warnings, '', "$literal has no POSIX warning");
}

my $unicode_error = eval q{use utf8; qr/[[=foo=]]/; 1} ? '' : $@;
like(
    $unicode_error,
    qr/POSIX syntax \[= =\] is reserved for future extensions/,
    'reserved syntax is recognized in UTF-8 source',
);

my $byte_error = eval q{use bytes; qr/[[.foo.]]/; 1} ? '' : $@;
like(
    $byte_error,
    qr/POSIX syntax \[\. \.\] is reserved for future extensions/,
    'reserved syntax is recognized in byte source',
);

done_testing;
