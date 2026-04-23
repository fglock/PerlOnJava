use strict;
use warnings;
use Test::More;

use File::Spec;
use System::Command;

my @cmd = ( $^X, File::Spec->catfile( t => 'lines.pl' ) );

my $O = 1 + int rand 5;
my $E = 1 + int rand 5;
plan tests => $O + $E + 2;

# basic usage
my ( $o, $e ) = ( 1, 1 );
my $cmd = System::Command->new( @cmd, $O, $E );
$cmd->loop_on(
    stdout =>
      sub { like( shift, qr/^STDOUT line $o$/, "STDOUT line $o" ); $o++ },
    stderr =>
      sub { like( shift, qr/^STDERR line $e$/, "STDERR line $e" ); $e++ },
);

# early abort
my $c = 0;
$cmd = System::Command->new( @cmd, 10, 10 );
$cmd->loop_on(
    stdout => sub { return !( ++$c == 5 ) },
    stderr => ''
);
is( $c, 5, 'Aborted after 5 lines' );

like( $cmd->stdout->getline, qr/^STDOUT line 6/, 'Command still runs' );
