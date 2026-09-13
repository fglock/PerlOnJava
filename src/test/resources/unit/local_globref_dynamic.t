use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

$Issue1305::FH = 'outer scalar slot';
my $globref = \*Issue1305::FH;
my ($tempfile_handle, $filename) = tempfile();
close $tempfile_handle;

{
    local *$globref;
    is($Issue1305::FH, undef, 'localized glob starts with a fresh scalar slot');
    $Issue1305::FH = 'inner scalar slot';

    open *$globref, '>', $filename or die "open $filename: $!";
    print {$globref} "localized IO slot\n" or die "print $filename: $!";
    close *$globref or die "close $filename: $!";

    is($Issue1305::FH, 'inner scalar slot', 'localized glob retains its scalar slot');
}

is($Issue1305::FH, 'outer scalar slot', 'original glob is restored after the dynamic scope');
open my $reader, '<', $filename or die "read $filename: $!";
is(<$reader>, "localized IO slot\n", 'open through the localized glob reference writes to its IO slot');
close $reader;
unlink $filename;

done_testing;
