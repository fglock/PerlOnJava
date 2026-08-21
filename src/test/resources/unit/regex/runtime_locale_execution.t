use strict;
use warnings;
use utf8;
use POSIX qw(LC_CTYPE setlocale);
use Test::More;
use re 'eval';

my $original = setlocale(LC_CTYPE);
my ($single) = grep { defined setlocale(LC_CTYPE, $_) }
    qw(en_US.ISO8859-1 en_US.ISO8859-15 de_DE.ISO8859-1);
plan skip_all => 'no supported single-byte non-C locale' unless $single;
my ($utf8) = grep { defined setlocale(LC_CTYPE, $_) }
    qw(en_US.UTF-8 de_DE.UTF-8 C.UTF-8);

my $lower_byte = pack('C', 0xe4);
my $upper_byte = pack('C', 0xc4);
my $mixed = qr/^[[:alpha:]_]$/l;
my $nested_negation = qr/^[^\W_]$/l;
my $word = qr/^\w$/l;

setlocale(LC_CTYPE, 'C');
ok($lower_byte !~ $mixed, 'C locale excludes high byte from mixed alpha class');
ok($lower_byte !~ $nested_negation,
    'C locale applies nested class negation after runtime word membership');
ok($lower_byte !~ $word, 'compiled locale word class starts with C semantics');

setlocale(LC_CTYPE, $single);
ok($lower_byte =~ $mixed,
    'single-byte locale contributes runtime alpha term beside literal');
ok($lower_byte =~ $nested_negation,
    'single-byte locale applies nested negation to runtime membership');
ok($lower_byte =~ $word, 'same compiled word class observes locale switch');

setlocale(LC_CTYPE, 'C');
ok($lower_byte !~ $word, 'same compiled word class observes switch back to C');

my $inflight = qr/^(?{ setlocale(LC_CTYPE, $single) })\w$/l;
setlocale(LC_CTYPE, 'C');
ok($lower_byte =~ $inflight,
    'locale change in an embedded code block affects the same match');

setlocale(LC_CTYPE, 'C');
ok('ss' !~ /^\x{DF}$/il, 'C locale suppresses multi-character Unicode fold');
ok($upper_byte !~ /^\x{E4}$/il, 'C locale suppresses high-byte simple fold');
setlocale(LC_CTYPE, $single);
ok('ss' !~ /^\x{DF}$/il,
    'single-byte locale suppresses multi-character Unicode fold');
ok($upper_byte =~ /^\x{E4}$/il,
    'single-byte locale supplies high-byte simple fold');

SKIP: {
    skip 'no supported UTF-8 locale', 1 unless $utf8;
    setlocale(LC_CTYPE, $utf8);
    ok('ss' =~ /^\x{DF}$/il, 'UTF-8 locale enables Unicode multi-character fold');
}

setlocale(LC_CTYPE, $single);
my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    ok("\x{100}" =~ /^\w$/l,
        'wide subject uses Unicode class semantics in non-UTF-8 locale');
}
like(join('', @warnings), qr/Wide character \(U\+100\) in pattern match/,
    'wide subject emits the locale-specific runtime warning');

setlocale(LC_CTYPE, $original) if defined $original;
done_testing;
