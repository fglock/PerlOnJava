use strict;
use warnings;
use Test::More;
use Scalar::Readonly qw( readonly readonly_on readonly_off );

# A package scalar that was read-only when it was exported keeps one shared
# read-only flag: unlocking it through the importing package's alias must
# unlock the exported scalar itself.
package Lock::Source;
our $METHOD = 'initial';
sub lock_method { no strict 'refs'; Scalar::Readonly::readonly_on(${ __PACKAGE__ . '::METHOD' }) }
sub set_method  { $METHOD = shift }
sub export_method_to {
    my ($caller) = @_;
    no strict 'refs';
    *{"${caller}::METHOD"} = \$Lock::Source::METHOD;
}

package main;

Lock::Source::lock_method();
Lock::Source::export_method_to('main');
our $METHOD;

ok readonly($Lock::Source::METHOD), 'the exported scalar starts read-only';
readonly_off($METHOD);
ok !readonly($Lock::Source::METHOD),
    'readonly_off through the importing alias unlocks the exported scalar';
ok !readonly($METHOD), 'the importing alias reports the unlocked state';

Lock::Source::set_method('after unlock');
is $Lock::Source::METHOD, 'after unlock',
    'the exported package can assign once the alias unlocked it';

$METHOD = 'written through alias';
is $Lock::Source::METHOD, 'written through alias',
    'the importing alias writes the shared scalar after unlocking it';

done_testing;
