use strict;
use warnings;
use Test::More tests => 1;
use charnames ();

my %registered;
sub register_name {
    my ($name) = @_;
    die "duplicate registration for $name" if $registered{$name}++;
}

register_name('delimited');
register_name('quoted');

my @bracket_pairs =
    map { ref $_ ? $_ :
            /!/ ? [(do { my $x = $_; $x =~ s/!/TOP/;    $x },
                    do { my $x = $_; $x =~ s/!/BOTTOM/; $x })]
                : [(do { my $x = $_; $x =~ s/\?/LEFT/;  $x },
                    do { my $x = $_; $x =~ s/\?/RIGHT/; $x })] }
        '? PARENTHESIS',
        '? SQUARE BRACKET',
        '? CURLY BRACKET',
        '? DOUBLE QUOTATION MARK',
        '? SINGLE QUOTATION MARK',
        '! PARENTHESIS';

@bracket_pairs = grep {
    defined charnames::string_vianame($_->[0]) &&
    defined charnames::string_vianame($_->[1])
} @bracket_pairs;

register_name('bquoted') if @bracket_pairs;

is_deeply([sort keys %registered], [qw(bquoted delimited quoted)],
    'lazy-sub verifier fallback occurs before enclosing side effects');
