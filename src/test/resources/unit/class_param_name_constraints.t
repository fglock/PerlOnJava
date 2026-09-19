use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my $local_duplicate = eval q{
    class ParamNameDuplicateLocal {
        field $first :param(shared);
        field $second :param(shared);
    }
    1;
};
ok(!$local_duplicate, 'two fields in one class cannot share a parameter name');
like($@, qr/Cannot assign :param\(shared\) to field \$second because that name is already in use/,
    'local duplicate identifies the parameter and field');

my $inherited_duplicate = eval q{
    class ParamNameDuplicateParent { field $first :param(shared); }
    class ParamNameDuplicateChild :isa(ParamNameDuplicateParent) {
        field $second :param(shared);
    }
    1;
};
ok(!$inherited_duplicate, 'a child field cannot reuse an inherited parameter name');
like($@, qr/Cannot assign :param\(shared\) to field \$second because that name is already in use/,
    'inherited duplicate identifies the parameter and field');

done_testing;
