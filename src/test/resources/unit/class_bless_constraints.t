use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

class BlessConstraints {}

my $ok = eval { bless [], 'BlessConstraints'; 1 };
ok(!$ok, 'bless into a class is rejected');
like($@, qr/Attempt to bless into a class/, 'class blessing has Perl diagnostic');

my $instance = BlessConstraints->new;
isa_ok($instance, 'BlessConstraints', 'generated constructor can create a class instance');

my $rebless_ok = eval { bless $instance, 'main'; 1 };
ok(!$rebless_ok, 'reblessing a class instance is rejected');
like($@, qr/Can't bless an object reference/, 'class instance reblessing has Perl diagnostic');

my $reopen_ok = eval q{
    class BlessConstraints {}
    1;
};
ok(!$reopen_ok, 'a class cannot be reopened');
like($@, qr/Cannot reopen existing class "BlessConstraints"/, 'class reopening has Perl diagnostic');

my $bad_reader_ok = eval q{
    class BadReaderAccessor { field $value :reader(not-valid) }
    1;
};
ok(!$bad_reader_ok, 'invalid reader method name is rejected');
like($@, qr/"not-valid" is not a valid name for a generated method/,
    'reader diagnostic identifies invalid method name');

my $bad_writer_ok = eval q{
    class BadWriterAccessor { field $value :writer(not-valid) }
    1;
};
ok(!$bad_writer_ok, 'invalid writer method name is rejected');
like($@, qr/"not-valid" is not a valid name for a generated method/,
    'writer diagnostic identifies invalid method name');

my $array_writer_ok = eval q{
    class ArrayWriterAccessor { field @value :writer }
    1;
};
ok(!$array_writer_ok, 'writer on an array field is rejected');
like($@, qr/Cannot apply a :writer attribute to a non-scalar field/,
    'array writer has Perl diagnostic');

my $hash_writer_ok = eval q{
    class HashWriterAccessor { field %value :writer }
    1;
};
ok(!$hash_writer_ok, 'writer on a hash field is rejected');
like($@, qr/Cannot apply a :writer attribute to a non-scalar field/,
    'hash writer has Perl diagnostic');

done_testing;
