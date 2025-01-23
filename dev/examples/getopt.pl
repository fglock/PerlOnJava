use Getopt::Long qw(GetOptions);
use feature 'say';
use strict;

GetOptions( 'verbose:s' => \my $verbose );

say $verbose;

