use strict;
use warnings;
use utf8;
use POSIX qw(LC_CTYPE setlocale);
use Test::More;

no warnings 'experimental::regex_sets';

my $original = setlocale(LC_CTYPE);
my $pattern = qr/\A(?[ [A] + [\x{100}] ])\z/l;

sub select_locale {
    my ($want_utf8, @candidates) = @_;
    for my $candidate (@candidates) {
        my $selected = setlocale(LC_CTYPE, $candidate);
        next unless defined $selected;
        my $is_utf8 = $selected =~ /UTF-?8/i ? 1 : 0;
        return $selected if $is_utf8 == $want_utf8;
    }
    return;
}

my $utf8_locale = select_locale(1,
    qw(en_US.UTF-8 C.UTF-8 nl_NL.UTF-8 de_DE.UTF-8));
SKIP: {
    skip 'no UTF-8 LC_CTYPE locale is available', 3 unless defined $utf8_locale;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    ok('A' =~ $pattern, 'extended class under /l matches ASCII in a UTF-8 locale');
    ok("\x{100}" =~ $pattern,
        'extended class under /l uses Unicode rules in a UTF-8 locale');
    is(join('', @warnings), '', 'UTF-8 locale execution emits no locale warning');
}

my $byte_locale = select_locale(0,
    qw(en_US.ISO8859-1 en_US.US-ASCII C POSIX));
SKIP: {
    skip 'no non-UTF-8 LC_CTYPE locale is available', 3 unless defined $byte_locale;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    ok('A' =~ $pattern, 'extended class under /l still matches ASCII in a byte locale');
    ok("\x{100}" =~ $pattern,
        'extended class under /l keeps Unicode semantics in a byte locale');
    like(join('', @warnings), qr/locale.*(?:UTF-?8|Unicode)|(?:UTF-?8|Unicode).*locale/i,
        'non-UTF-8 locale execution emits the locale/Unicode contract warning');
}

setlocale(LC_CTYPE, $original) if defined $original;
done_testing;
