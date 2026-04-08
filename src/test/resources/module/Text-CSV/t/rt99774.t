
$^W = 1;
use strict;

use Test::More tests => 8;


BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $csv = Text::CSV->new ( { binary => 1, sep_char => ';', allow_whitespace => 1, quote_char => '"' } );

# https://rt.cpan.org/Public/Bug/Display.html?id=99774

while ( my $line = <DATA> ) {
    my $text = $line;
    chomp($text); $text =~ s/"//g;
    my $expect = [ split/;/, $text ];

    $csv->parse($line);
    is_deeply( [$csv->fields], $expect, $line );
}

# https://rt.cpan.org/Public/Bug/Display.html?id=92509

for my $allow_whitespace ( 0, 1 ) {
    $csv = Text::CSV->new ( { allow_whitespace => $allow_whitespace } );
    $csv->parse(q{"value1","0","value3"});
    is_deeply( [$csv->fields], ["value1","0","value3"], 'allow_whitespace:' . $allow_whitespace );
}


__DATA__
"data_quality_id";"language_version_id";"name"
"0";"2";"0%"
"10";"2";"33%"
"20";"2";"66%"
"30";"2";"100%"
