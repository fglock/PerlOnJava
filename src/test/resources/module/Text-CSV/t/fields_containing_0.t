#!/usr/bin/perl

=head1 DESCRIPTION

This is a test program that succeeds with Text::CSV_PP and fails with
Text::CSV_XS. The Text::CSV_XS behaviour is the correct one.

=head1 CREDITS AND LICENSE

The sample data (now anonymised) and a test program were contributed by
blue_sky on Freenode’s
#perl channel as part of a problem report with Text::CSV_PP based on the
Text::CSV documentation. License is open source and compatible with the license
of Text::CSV.

Converted into a test program by Shlomi Fish ( L<http://www.shlomifish.org/> )
, while disclaiming all explicit or implicit copyright ownership on the
modifications.

==head1 MODIFICATION

modified by makamaka for old perl.

=cut

#use warnings;
$^W = 1;
use strict;

use Test::More tests => 4;

my $FALSE = 0;
# my $USE_XS = $ENV{'USE_TEXT_CSV_XS'};
my $USE_XS = $FALSE;

use Text::CSV_PP;
use Data::Dumper qw(Dumper);

END { unlink '_fc0_test.csv'; }

if ($USE_XS)
{
    require Text::CSV_XS;
}

{
    my $csv_text = <<'EOF';
"DIVISION CODE", "DIVISION DESCRIPTION", "CUSTOMER CODE", "CUSTOMER NAME", "SHORT NAME", "ADDRESS LINE 1", "ADDRESS LINE 2", "ADDRESS LINE 3", "TOWN", "COUNTY", "POST CODE", "COUNTRY", "GRID REF", "TELEPHONE", "AGENT CODE", "YEAR TO DATE SALES"
"1", "UK", "Lambda", "Gambda Noo", "Foo", "Quad", "Rectum", "", "Eingoon", "Land", "Simplex", "", "", "099 999", "", 0.00
EOF

#    open my $IF, "<", \$csv_text;
    my $IF;
    open  $IF, ">_fc0_test.csv" or die "_fc0_test.csv: $!";
    print $IF $csv_text;
    close $IF;

    open  $IF, "<_fc0_test.csv" or die "_fc0_test.csv: $!";

    my $csv = ($USE_XS ? "Text::CSV_XS" : "Text::CSV_PP")->new({
            allow_whitespace    => 1,
            allow_loose_escapes => 1,
        }) or die "Cannot use CSV: ".Text::CSV->error_diag();

    $csv->column_names( $csv->getline($IF) );

    {
        my $first_line = $csv->getline_hr($IF);

        # TEST
        is ($first_line->{'POST CODE'}, 'Simplex',
            "First line POST CODE"
        );

        # TEST
        is ($first_line->{'COUNTRY'}, '',
            "First line COUNTRY",
        );

        # TEST
        is ($first_line->{'GRID REF'}, '',
            "First line GRID REF",
        );

        # TEST
        is ($first_line->{'TELEPHONE'}, '099 999',
            "First line TELEPHONE",
        );
    }
    close($IF);
}
