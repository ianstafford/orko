import React, { Component } from "react"

import { connect } from "react-redux"

import Section from "../components/primitives/Section"
import Modal from "../components/primitives/Modal"
import Href from "../components/primitives/Href"
import * as uiActions from "../store/ui/actions"
import * as coinsActions from "../store/coins/actions"
import * as focusActions from "../store/focus/actions"
import { Icon } from "semantic-ui-react"
import Input from "../components/primitives/Input.js"
import Form from "../components/primitives/Form"
import Button from "../components/primitives/Button"
import { isValidNumber, formatNumber } from "../util/numberUtils"
import FormButtonBar from "../components/primitives/FormButtonBar"

class SetReferencePriceContainer extends Component {
  state = {
    price: ""
  }

  onChangePrice = e => {
    this.setState({ price: e.target.value })
  }

  onSubmit = () => {
    this.props.dispatch(
      coinsActions.setReferencePrice(this.props.coin, this.state.price)
    )
    this.props.dispatch(uiActions.closeReferencePrice())
    this.setState({ price: "" })
  }

  onClear = () => {
    this.props.dispatch(coinsActions.setReferencePrice(this.props.coin, null))
    this.props.dispatch(uiActions.closeReferencePrice())
    this.setState({ price: "" })
  }

  onFocus = () => {
    this.props.dispatch(
      focusActions.setUpdateAction(value => this.setState({ price: value }))
    )
  }

  render() {
    if (!this.props.coin) return null
    const ready =
      this.state.price &&
      isValidNumber(this.state.price) &&
      this.state.price > 0
    return (
      <Modal mobile={this.props.mobile}>
        <Section
          id="referencePrice"
          heading={"Set reference price for " + this.props.coin.name}
          buttons={() => (
            <Href
              title="Close"
              onClick={() =>
                this.props.dispatch(uiActions.closeReferencePrice())
              }
            >
              <Icon fitted name="close" />
            </Href>
          )}
        >
          <Form>
            <Input
              id="price"
              error={ready}
              label="Reference price"
              type="number"
              placeholder="Enter price..."
              value={
                this.state.price ? this.state.price : this.props.referencePrice
              }
              onChange={this.onChangePrice}
              onFocus={this.onFocus}
            />
            <FormButtonBar>
              <Button data-orko="doClear" onClick={this.onClear}>Clear</Button>
              <Button data-orko="doSubmit" disabled={!ready} onClick={this.onSubmit}>
                Set
              </Button>
            </FormButtonBar>
          </Form>
        </Section>
      </Modal>
    )
  }
}

function mapStateToProps(state) {
  const coinMetadata =
    state.coins.meta && state.ui.referencePriceCoin
      ? state.coins.meta[state.ui.referencePriceCoin.key]
      : undefined
  const priceScale = coinMetadata ? coinMetadata.priceScale : 8
  const referencePrice = state.ui.referencePriceCoin
    ? state.coins.referencePrices[state.ui.referencePriceCoin.key]
    : null
  return {
    coin: state.ui.referencePriceCoin,
    referencePrice: formatNumber(referencePrice, priceScale, "")
  }
}

export default connect(mapStateToProps)(SetReferencePriceContainer)
