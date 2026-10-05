use strict;
use warnings;
use utf8;
no strict 'refs';
use Test::More;
sub оઓnḲ () { "Value" }
delete $::{оઓnḲ};
$::{оઓnḲ} = \"Value";

*{"ga_ㄕƚo잎"} = \&{"оઓnḲ"};

is (ref $::{ga_ㄕƚo잎}, 'SCALAR', "Export of proxy constant as is");
is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'ga_ㄕƚo잎', "Value", "Constant has correct value");
is (ref $::{ga_ㄕƚo잎}, 'SCALAR',
    "Inlining of constant doesn't change representation");

delete $::{ga_ㄕƚo잎};

eval 'sub ga_ㄕƚo잎 (); 1' or die $@;
is ($::{ga_ㄕƚo잎}, '', "Prototype is stored as an empty string");

# Check that a prototype expands.
*{"ga_ㄕƚo잎"} = \&{"оઓnḲ"};

is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'ga_ㄕƚo잎', "Value", "Constant has correct value");
is (ref \$::{ga_ㄕƚo잎}, 'GLOB', "Symbol table has full typeglob");


@::zᐓｔ = ('Zᐓｔ!');

# Check that assignment to an existing typeglob works
{
  my $w = '';
  local $SIG{__WARN__} = sub { $w = $_[0] };
  *{"zᐓｔ"} = \&{"оઓnḲ"};
  is($w, '', "Should be no warning");
}

is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'zᐓｔ', "Value", "Constant has correct value");
is (ref \$::{zᐓｔ}, 'GLOB', "Symbol table has full typeglob");
is (join ('!', @::zᐓｔ), 'Zᐓｔ!', "Existing array still in typeglob");

sub Ṩp맅싵Ş () {
    "Traditional";
}

# Check that assignment to an existing subroutine works
{
  my $w = '';
  local $SIG{__WARN__} = sub { $w = $_[0] };
  *{"Ṩp맅싵Ş"} = \&{"оઓnḲ"};
  like($w, qr/^Constant subroutine main::Ṩp맅싵Ş redefined/,
       "Redefining a constant sub should warn");
}

is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'Ṩp맅싵Ş', "Value", "Constant has correct value");
is (ref \$::{Ṩp맅싵Ş}, 'GLOB', "Symbol table has full typeglob");

# Check that assignment to an existing typeglob works
{
  my $w = '';
  local $SIG{__WARN__} = sub { $w = $_[0] };
  *{"plუᒃ"} = [];
  *{"plუᒃ"} = \&{"оઓnḲ"};
  is($w, '', "Should be no warning");
}

is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'plუᒃ', "Value", "Constant has correct value");
is (ref \$::{plუᒃ}, 'GLOB', "Symbol table has full typeglob");

my $gr = eval '\*plუᒃ' or die;

{
  my $w = '';
  local $SIG{__WARN__} = sub { $w = $_[0] };
  *{$gr} = \&{"оઓnḲ"};
  is($w, '', "Redefining a constant sub to another constant sub with the same underlying value should not warn (It's just re-exporting, and that was always legal)");
}

is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'plუᒃ', "Value", "Constant has correct value");
is (ref \$::{plუᒃ}, 'GLOB', "Symbol table has full typeglob");

# Non-void context should defeat the optimisation, and will cause the original
# to be promoted (what change 26482 intended)
my $result;
{
  my $w = '';
  local $SIG{__WARN__} = sub { $w = $_[0] };
  $result = *{"aẈʞƙʞƙʞƙ"} = \&{"оઓnḲ"};
  is($w, '', "Should be no warning");
}

is (ref \$result, 'GLOB',
    "Non void assignment should still return a typeglob");

is (ref \$::{оઓnḲ}, 'GLOB', "This export does affect original");
is (eval 'plუᒃ', "Value", "Constant has correct value");
is (ref \$::{plუᒃ}, 'GLOB', "Symbol table has full typeglob");

delete $::{оઓnḲ};
$::{оઓnḲ} = \"Value";

sub non_dangling {
  my $w = '';
  local $SIG{__WARN__} = sub { $w = $_[0] };
  *{"z앞"} = \&{"оઓnḲ"};
  is($w, '', "Should be no warning");
}

non_dangling();
is (ref $::{оઓnḲ}, 'SCALAR', "Export doesn't affect original");
is (eval 'z앞', "Value", "Constant has correct value");
is (ref $::{z앞}, 'SCALAR', "Exported target is also a PCS");

sub dangling {
  local $SIG{__WARN__} = sub { die $_[0] };
  *{"ビfᶠ"} = \&{"оઓnḲ"};
}

dangling();
is (ref \$::{оઓnḲ}, 'GLOB', "This export does affect original");
done_testing();
