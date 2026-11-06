use strict;
use warnings;
use threads;
use threads::shared;

print "1..8\n";

{
    package SharedNestedHashObject;

    sub new {
        return bless(threads::shared::shared_clone({}), shift);
    }

    sub DESTROY {
        $main::destroyed++;
    }
}

{
    package SharedNestedArrayObject;

    sub new {
        return bless(threads::shared::shared_clone([]), shift);
    }

    sub DESTROY {
        $main::destroyed++;
    }
}

our $destroyed :shared = 0;
my @shared_array :shared;
my %shared_hash :shared;

$shared_array[0] = SharedNestedHashObject->new();
$shared_array[0] = undef;
print $destroyed == 1
    ? "ok 1 - undef releases nested hash object from shared array\n"
    : "not ok 1 - undef releases nested hash object from shared array ($destroyed)\n";

$shared_array[0] = SharedNestedArrayObject->new();
$shared_array[0] = SharedNestedArrayObject->new();
print $destroyed == 2
    ? "ok 2 - replacement releases nested array object from shared array\n"
    : "not ok 2 - replacement releases nested array object from shared array ($destroyed)\n";

$shared_hash{object} = SharedNestedHashObject->new();
$shared_hash{object} = undef;
print $destroyed == 3
    ? "ok 3 - undef releases nested hash object from shared hash\n"
    : "not ok 3 - undef releases nested hash object from shared hash ($destroyed)\n";

$shared_hash{object} = SharedNestedArrayObject->new();
$shared_hash{object} = SharedNestedArrayObject->new();
print $destroyed == 4
    ? "ok 4 - replacement releases nested array object from shared hash\n"
    : "not ok 4 - replacement releases nested array object from shared hash ($destroyed)\n";

# Keep each canonical object reachable until its slot is explicitly cleared.
@shared_array = (SharedNestedHashObject->new());
%shared_hash = (object => SharedNestedArrayObject->new());
my $before_clear = $destroyed;
@shared_array = ();
%shared_hash = ();
print $destroyed == $before_clear + 2
    ? "ok 5 - clearing shared aggregates releases nested objects\n"
    : "not ok 5 - clearing shared aggregates releases nested objects ($destroyed)\n";

$shared_array[0] = SharedNestedHashObject->new();
my $before_delete = $destroyed;
delete $shared_array[0];
print $destroyed == $before_delete + 1
    ? "ok 6 - deleting shared array slot releases nested object\n"
    : "not ok 6 - deleting shared array slot releases nested object ($destroyed)\n";

$shared_hash{object} = SharedNestedArrayObject->new();
my $before_hash_delete = $destroyed;
delete $shared_hash{object};
print $destroyed == $before_hash_delete + 1
    ? "ok 7 - deleting shared hash slot releases nested object\n"
    : "not ok 7 - deleting shared hash slot releases nested object ($destroyed)\n";

print "ok 8 - shared nested object release checks completed\n";
