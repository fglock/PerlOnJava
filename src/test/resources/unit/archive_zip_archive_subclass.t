use strict;
use warnings;

use Archive::Zip;
use Test::More;

{
    package Local::Archive;
    our @ISA = qw(Archive::Zip::Archive);
    sub new { shift->Archive::Zip::Archive::new(@_) }
}

my $archive = Local::Archive->new;
isa_ok($archive, 'Local::Archive', 'Archive::Zip::Archive constructor preserves subclasses');
is($archive->numberOfMembers, 0, 'subclass inherits archive methods');
$archive->addString('one', 'one.txt');
$archive->addString('two', 'two.txt');
is(scalar($archive->memberNames), 2, 'memberNames returns its count in scalar context');
is(scalar($archive->members), 2, 'members returns its count in scalar context');

done_testing;
