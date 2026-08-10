use strict;
use warnings;
use Test::More tests => 2;
use File::Temp;

my $file = File::Temp->new;
ok(defined $$file, 'File::Temp object has a defined scalar glob slot');

sub accepts_file_temp_glob {
    my ($filename) = @_;
    return if !defined $$filename;
    return $filename->filename;
}

is(accepts_file_temp_glob($file), $file->filename,
   'legacy scalar-glob guard accepts File::Temp object');
