use strict;
use warnings;
use Test::More tests => 3;
use Data::Dumper ();

BEGIN {
    package Local::DumperFilter;
    use Filter::Simple;
    my $DUMPER_FUNCTION = 'Dumper';
    my $COMMA = qr/(?:,|=>)/;
    my $ATOM = qr/(?!\d)[[:word:]]+/;
    my $SEP = qr/::/;
    my $NAME = qr/$SEP?$ATOM(?:$SEP$ATOM)*/;
    my $SCALAR = qr/\$$NAME/;
    my $VAR = qr/$SCALAR/;
    my $END_STMT = qr/(?=\s*[;}])/;
    my $ARG_LIST = qr/$VAR(?:\s*$COMMA\s*$VAR)*$END_STMT/;
    my $PAREN_LIST = qr/\([^)]+\)/;

    sub import {
        my ($class, %args) = @_;
        $DUMPER_FUNCTION = $args{as} if $args{as};
    }

    FILTER_ONLY executable => sub {
        s{$DUMPER_FUNCTION\s*($PAREN_LIST|$ARG_LIST)}{
            my $args = $1;
            $args =~ s/^\((.*)\)$/$1/s;
            "(Data::Dumper->Dump([$args], ['\$scalar']))";
        }gex;
    };

    $INC{'Local/DumperFilter.pm'} = __FILE__;
    Test::More::use_ok('Local::DumperFilter', as => 'Display');
}

my $scalar = 'source filtering works';
my $parenthesized = Display($scalar);
my $unparenthesized = Display $scalar;
my $expected = Data::Dumper->Dump([$scalar], ['$scalar']);
is($parenthesized, $expected, 'Filter::Simple rewrites parenthesized calls into executable Perl');
is($unparenthesized, $expected, 'Filter::Simple rewrites unparenthesized calls into executable Perl');
