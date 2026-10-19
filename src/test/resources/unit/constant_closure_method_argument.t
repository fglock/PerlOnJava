use strict;
use warnings;
use Test::More;

# Regression for Rose::HTML::Objects' add_message pattern (#1785): install a
# constant CV from a method argument, then keep using that argument as hash key
# and value in the remainder of the method.
{
    package ConstantClosureMethodArgument;

    my (%name_to_id, %id_to_name);

    sub imported { 0 }
    sub use_private_messages { 1 }
    sub message_name_to_id_map { \%name_to_id }
    sub message_id_to_name_map { \%id_to_name }
    sub _message_names { [] }

    sub add_message {
        my ($class, $name, $id) = @_;

        unless ($class->imported($name)) {
            if (exists $class->message_name_to_id_map->{$name}
                    && $class->message_name_to_id_map->{$name} != $id) {
                die "message name collision";
            }
            if (exists $class->message_id_to_name_map->{$id}
                    && $class->message_id_to_name_map->{$id} ne $name) {
                die "message id collision";
            }
        }

        {
            no strict 'refs';
            my $const = "${class}::$name";
            unless ($class->can($name) || defined &$const) {
                *{"${class}::$name"} = sub () { $id };
            }
        }

        unless (exists $class->message_name_to_id_map->{$name}) {
            push @{$class->_message_names}, $name;
        }
        $class->message_id_to_name_map->{$id} = $name;
        $class->message_name_to_id_map->{$name} = $id;
    }
}

ConstantClosureMethodArgument->add_message('answer', 42);
is(ConstantClosureMethodArgument->answer(), 42,
    'constant closure captures a read-only method argument');

done_testing;
